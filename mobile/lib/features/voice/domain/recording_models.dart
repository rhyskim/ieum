/// 녹음 시간 규칙(ARCHITECTURE §3.3).
const Duration recordingWarnAt = Duration(seconds: 150);
const Duration recordingMaxDuration = Duration(seconds: 180);

enum RecordingPhase { idle, recording, paused, completed }

/// 화면이 안내 문구로 바꿔 보여 주는 사유. 에러 대신 안내로 처리한다(CLAUDE.md §5).
enum RecordingNotice {
  none,
  permissionDenied,
  permissionPermanentlyDenied,
  micBusy,
  storageFull,
  interruptedByCall,
  pausedInBackground,
  unknown,
}

class RecordingState {
  const RecordingState({
    this.phase = RecordingPhase.idle,
    this.elapsed = Duration.zero,
    this.filePath,
    this.reachedMax = false,
    this.callPromptPending = false,
    this.notice = RecordingNotice.none,
  });

  final RecordingPhase phase;

  /// 일시정지 시간을 뺀 실제 녹음 시간.
  final Duration elapsed;

  /// 완료된 녹음 파일. 어떤 실패 상황에서도 지우지 않는다.
  final String? filePath;

  /// 180초에 도달해 자동 완료됐는지.
  final bool reachedMax;

  /// 전화 유도 모달을 아직 띄우지 않았는지. 화면이 [RecordingController.consumeCallPrompt]로 한 번만 소비한다.
  final bool callPromptPending;
  final RecordingNotice notice;

  bool get isWarning =>
      (phase == RecordingPhase.recording || phase == RecordingPhase.paused) && elapsed >= recordingWarnAt;

  Duration get remaining {
    final Duration left = recordingMaxDuration - elapsed;
    return left.isNegative ? Duration.zero : left;
  }

  RecordingState copyWith({
    RecordingPhase? phase,
    Duration? elapsed,
    String? filePath,
    bool? reachedMax,
    bool? callPromptPending,
    RecordingNotice? notice,
  }) =>
      RecordingState(
        phase: phase ?? this.phase,
        elapsed: elapsed ?? this.elapsed,
        filePath: filePath ?? this.filePath,
        reachedMax: reachedMax ?? this.reachedMax,
        callPromptPending: callPromptPending ?? this.callPromptPending,
        notice: notice ?? this.notice,
      );
}
