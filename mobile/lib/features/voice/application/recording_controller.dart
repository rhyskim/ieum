import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';
import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

const Duration _tickInterval = Duration(milliseconds: 200);

final AutoDisposeNotifierProvider<RecordingController, RecordingState> recordingControllerProvider =
    AutoDisposeNotifierProvider<RecordingController, RecordingState>(RecordingController.new);

/// 녹음 상태 머신: idle → recording ⇄ paused → completed(→ 다시 recording).
/// 파일은 어떤 실패에서도 지우지 않는다. 지우는 경우는 새 녹음이 성공적으로 시작된 뒤 이전 파일 교체뿐이다.
class RecordingController extends AutoDisposeNotifier<RecordingState> {
  late final AudioRecorderPort _recorder = ref.read(audioRecorderProvider);
  late final RecordingClock _clock = ref.read(recordingClockProvider);

  StreamSubscription<void>? _tickSub;
  StreamSubscription<InterruptionKind>? _interruptionSub;
  Duration _accumulated = Duration.zero;
  DateTime? _segmentStart;
  String? _currentPath;
  bool _finalizing = false;

  /// 녹음기가 열려 있는 동안 true. dispose 시 state를 읽지 않기 위한 값.
  bool _live = false;

  @override
  RecordingState build() {
    _interruptionSub = _recorder.interruptions.listen(_onInterruption);
    ref.onDispose(() {
      _tickSub?.cancel();
      _interruptionSub?.cancel();
      // 녹음 중 화면을 떠나면 파일을 살리기 위해 정상 종료만 한다(삭제하지 않음).
      if (_live) {
        unawaited(_recorder.stop().catchError((Object _) => null));
      }
    });
    return const RecordingState();
  }

  Duration get _elapsedNow {
    final DateTime? start = _segmentStart;
    return start == null ? _accumulated : _accumulated + _clock.now().difference(start);
  }

  Future<void> start() async {
    if (state.phase == RecordingPhase.recording || state.phase == RecordingPhase.paused) return;
    final String? previous = state.filePath;
    final String path;
    try {
      path = await _recorder.newFilePath();
      await _recorder.start(path);
    } on RecorderException catch (e) {
      // 시작 실패: 이전 녹음(있다면)은 그대로 둔다.
      state = state.copyWith(notice: e.notice);
      return;
    }
    // 새 녹음이 실제로 시작된 뒤에야 이전 파일을 교체한다.
    if (previous != null) {
      unawaited(_recorder.discard(previous).catchError((Object _) {}));
    }
    _currentPath = path;
    _accumulated = Duration.zero;
    _segmentStart = _clock.now();
    _finalizing = false;
    _live = true;
    state = const RecordingState(phase: RecordingPhase.recording);
    _tickSub?.cancel();
    _tickSub = _clock.ticks(_tickInterval).listen((_) => _onTick());
  }

  Future<void> pause({RecordingNotice notice = RecordingNotice.none}) async {
    if (state.phase != RecordingPhase.recording) return;
    _accumulated = _elapsedNow;
    _segmentStart = null;
    _tickSub?.cancel();
    state = state.copyWith(phase: RecordingPhase.paused, elapsed: _accumulated, notice: notice);
    try {
      await _recorder.pause();
    } on RecorderException catch (e) {
      await _finalize(reachedMax: false, notice: e.notice);
    }
  }

  Future<void> resume() async {
    if (state.phase != RecordingPhase.paused) return;
    try {
      await _recorder.resume();
    } on RecorderException catch (e) {
      // 이어가지 못해도 지금까지의 녹음은 보존한다.
      await _finalize(reachedMax: false, notice: e.notice);
      return;
    }
    _segmentStart = _clock.now();
    state = state.copyWith(phase: RecordingPhase.recording, notice: RecordingNotice.none);
    _tickSub?.cancel();
    _tickSub = _clock.ticks(_tickInterval).listen((_) => _onTick());
  }

  /// 사용자가 직접 [완료]를 누른 경우.
  Future<void> finish() async {
    if (state.phase != RecordingPhase.recording && state.phase != RecordingPhase.paused) return;
    _accumulated = _elapsedNow;
    _segmentStart = null;
    await _finalize(reachedMax: false);
  }

  /// 앱이 백그라운드로 가면 일시정지(이어서 녹음은 사용자가 누른다).
  Future<void> onBackgrounded() => pause(notice: RecordingNotice.pausedInBackground);

  void dismissNotice() {
    if (state.notice != RecordingNotice.none) state = state.copyWith(notice: RecordingNotice.none);
  }

  /// 전화 유도 모달을 띄우기 직전에 호출한다. 처음 한 번만 true.
  bool consumeCallPrompt() {
    if (!state.callPromptPending) return false;
    state = state.copyWith(callPromptPending: false);
    return true;
  }

  void _onInterruption(InterruptionKind kind) {
    if (kind == InterruptionKind.begin) {
      unawaited(pause(notice: RecordingNotice.interruptedByCall));
    }
  }

  void _onTick() {
    if (state.phase != RecordingPhase.recording || _finalizing) return;
    final Duration elapsed = _elapsedNow;
    if (elapsed >= recordingMaxDuration) {
      _accumulated = recordingMaxDuration;
      _segmentStart = null;
      unawaited(_finalize(reachedMax: true));
      return;
    }
    state = state.copyWith(elapsed: elapsed);
  }

  Future<void> _finalize({required bool reachedMax, RecordingNotice notice = RecordingNotice.none}) async {
    if (_finalizing) return;
    _finalizing = true;
    _live = false;
    _tickSub?.cancel();
    String? path = _currentPath;
    RecordingNotice finalNotice = notice;
    try {
      path = await _recorder.stop() ?? path;
    } on RecorderException catch (e) {
      // 저장 공간 부족 등으로 마무리가 실패해도 경로는 유지해 파일을 보존한다.
      finalNotice = e.notice;
    }
    state = RecordingState(
      phase: RecordingPhase.completed,
      elapsed: _accumulated,
      filePath: path,
      reachedMax: reachedMax,
      callPromptPending: reachedMax,
      notice: finalNotice,
    );
  }
}
