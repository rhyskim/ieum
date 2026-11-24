import 'dart:async';
import 'dart:io';

import 'package:audio_session/audio_session.dart';
import 'package:ieum_mobile/core/voice/mic_permission_gateway.dart';
import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

/// `record` 기반 구현. AAC-LC `.m4a`, 64kbps, 모노, 22.05kHz(음성용). 실제 기기에서만 검증할 수 있다.
class RecordAudioRecorder implements AudioRecorderPort {
  RecordAudioRecorder(this._permission) {
    _interruptions = StreamController<InterruptionKind>.broadcast();
  }

  final MicPermissionGateway _permission;

  final AudioRecorder _recorder = AudioRecorder();
  late final StreamController<InterruptionKind> _interruptions;
  StreamSubscription<AudioInterruptionEvent>? _sessionSub;

  static const RecordConfig _config = RecordConfig(
    encoder: AudioEncoder.aacLc,
    bitRate: 64000,
    sampleRate: 22050,
    numChannels: 1,
    // 인터럽트는 audio_session으로 받아 컨트롤러가 일시정지를 직접 관리한다.
    audioInterruption: AudioInterruptionMode.none,
  );

  @override
  Future<String> newFilePath() async {
    final Directory dir = await getTemporaryDirectory();
    return '${dir.path}/voice_${DateTime.now().millisecondsSinceEpoch}.m4a';
  }

  @override
  Future<void> start(String path) async {
    switch (await _permission.request()) {
      case MicPermissionResult.granted:
        break;
      case MicPermissionResult.permanentlyDenied:
        throw const RecorderException(RecordingNotice.permissionPermanentlyDenied);
      case MicPermissionResult.denied:
        throw const RecorderException(RecordingNotice.permissionDenied);
    }

    try {
      final AudioSession session = await AudioSession.instance;
      await session.configure(const AudioSessionConfiguration.speech());
      _sessionSub ??= session.interruptionEventStream.listen(
        (AudioInterruptionEvent e) =>
            _interruptions.add(e.begin ? InterruptionKind.begin : InterruptionKind.end),
      );
      // 다른 앱이 마이크를 쓰고 있으면 활성화가 거절된다.
      if (!await session.setActive(true)) {
        throw const RecorderException(RecordingNotice.micBusy);
      }
      await _recorder.start(_config, path: path);
    } on RecorderException {
      rethrow;
    } on Object catch (e) {
      unawaited(_deactivateSession());
      throw RecorderException(_classify(e, fallback: RecordingNotice.micBusy), e);
    }
  }

  @override
  Future<void> pause() => _guard(_recorder.pause);

  @override
  Future<void> resume() => _guard(() async {
        // 인터럽트로 잃은 오디오 포커스를 다시 얻는다. 다른 앱이 쓰는 중이면 거절된다.
        final AudioSession session = await AudioSession.instance;
        if (!await session.setActive(true)) {
          throw const RecorderException(RecordingNotice.micBusy);
        }
        await _recorder.resume();
      });

  @override
  Future<String?> stop() async {
    try {
      return await _recorder.stop();
    } on Object catch (e) {
      throw RecorderException(_classify(e), e);
    } finally {
      unawaited(_deactivateSession());
    }
  }

  Future<void> _deactivateSession() async {
    try {
      await (await AudioSession.instance).setActive(false);
    } on Object {
      // 세션 정리 실패는 녹음 파일에 영향이 없다.
    }
  }

  @override
  Future<void> discard(String path) async {
    final File file = File(path);
    if (await file.exists()) await file.delete();
  }

  @override
  Stream<InterruptionKind> get interruptions => _interruptions.stream;

  @override
  Stream<double> get levels => _levels;

  // 화면이 다시 빌드돼도 스트림을 새로 만들지 않도록 한 번만 만든다.
  // current는 dBFS(-160..0). 음성 범위인 -50dB 이상만 0..1로 편다.
  late final Stream<double> _levels = _recorder
      .onAmplitudeChanged(const Duration(milliseconds: 100))
      .map((Amplitude a) => ((a.current + 50) / 50).clamp(0.0, 1.0))
      .asBroadcastStream();

  Future<void> _guard(Future<void> Function() action) async {
    try {
      await action();
    } on RecorderException {
      rethrow;
    } on Object catch (e) {
      throw RecorderException(_classify(e), e);
    }
  }

  RecordingNotice _classify(Object error, {RecordingNotice fallback = RecordingNotice.unknown}) {
    if (error is FileSystemException && error.osError?.errorCode == 28) return RecordingNotice.storageFull;
    final String text = error.toString().toLowerCase();
    if (text.contains('enospc') || text.contains('no space')) return RecordingNotice.storageFull;
    return fallback;
  }

  @override
  Future<void> dispose() async {
    await _sessionSub?.cancel();
    await _interruptions.close();
    await _recorder.dispose();
  }
}
