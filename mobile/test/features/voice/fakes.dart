import 'dart:async';

import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

/// 실제 마이크 없이 녹음 상태 머신을 구동하는 가짜 녹음기.
class FakeRecorder implements AudioRecorderPort {
  int _seq = 0;
  RecorderException? startError;
  RecorderException? stopError;
  RecorderException? pauseError;
  RecorderException? resumeError;
  final List<String> started = <String>[];
  final List<String> discarded = <String>[];
  int stopCalls = 0;
  final StreamController<InterruptionKind> interruptionController = StreamController<InterruptionKind>.broadcast();

  @override
  Future<String> newFilePath() async => 'fake/voice_${++_seq}.m4a';

  @override
  Future<void> start(String path) async {
    if (startError != null) throw startError!;
    started.add(path);
  }

  @override
  Future<void> pause() async {
    if (pauseError != null) throw pauseError!;
  }

  @override
  Future<void> resume() async {
    if (resumeError != null) throw resumeError!;
  }

  @override
  Future<String?> stop() async {
    stopCalls++;
    if (stopError != null) throw stopError!;
    return started.isEmpty ? null : started.last;
  }

  @override
  Future<void> discard(String path) async => discarded.add(path);

  @override
  Stream<InterruptionKind> get interruptions => interruptionController.stream;

  @override
  Stream<double> get levels => const Stream<double>.empty();

  @override
  Future<void> dispose() async {}
}

/// 직접 시간을 흘려 보내는 시계. [advance]가 200ms 단위로 틱을 발생시킨다.
class FakeClock implements RecordingClock {
  DateTime _now = DateTime.utc(2026, 1, 1);
  final StreamController<void> _controller = StreamController<void>.broadcast(sync: true);

  @override
  DateTime now() => _now;

  @override
  Stream<void> ticks(Duration interval) => _controller.stream;

  /// 녹음 중이 아닐 때(일시정지)도 벽시계는 흐른다.
  void advance(Duration total, {Duration step = const Duration(milliseconds: 200)}) {
    Duration left = total;
    while (left > Duration.zero) {
      final Duration d = left < step ? left : step;
      _now = _now.add(d);
      left -= d;
      _controller.add(null);
    }
  }
}

class FakePreviewPlayer implements PreviewPlayerPort {
  final List<String> loaded = <String>[];
  int plays = 0;
  int pauses = 0;
  final List<Duration> seeks = <Duration>[];
  final StreamController<bool> _playing = StreamController<bool>.broadcast();

  @override
  Future<void> load(String path) async => loaded.add(path);

  @override
  Future<void> play() async {
    plays++;
    _playing.add(true);
  }

  @override
  Future<void> pause() async {
    pauses++;
    _playing.add(false);
  }

  @override
  Future<void> seek(Duration position) async => seeks.add(position);

  @override
  Stream<Duration> get position => const Stream<Duration>.empty();

  @override
  Stream<Duration?> get duration => Stream<Duration?>.value(const Duration(seconds: 180));

  @override
  Stream<bool> get playing => _playing.stream;

  @override
  Stream<bool> get completed => const Stream<bool>.empty();

  @override
  Future<void> dispose() async {}
}

class FakePhoneLauncher implements PhoneLauncher {
  final List<String> called = <String>[];
  bool result = true;

  @override
  Future<bool> call(String phone) async {
    called.add(phone);
    return result;
  }
}

const RecorderException permissionDenied = RecorderException(RecordingNotice.permissionDenied);
