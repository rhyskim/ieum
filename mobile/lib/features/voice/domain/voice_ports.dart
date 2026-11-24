import 'package:ieum_mobile/features/voice/domain/recording_models.dart';

/// 녹음기 실패 종류. 구현체가 플랫폼 오류를 이 값으로 바꿔 던진다.
class RecorderException implements Exception {
  const RecorderException(this.notice, [this.cause]);

  final RecordingNotice notice;
  final Object? cause;

  @override
  String toString() => 'RecorderException($notice, $cause)';
}

enum InterruptionKind { begin, end }

/// 실제 마이크 없이 테스트할 수 있도록 녹음 패키지를 가린다.
abstract interface class AudioRecorderPort {
  /// 권한 확인(필요하면 요청) 후 [path]에 녹음을 시작한다. 실패 시 [RecorderException].
  Future<void> start(String path);

  Future<void> pause();

  Future<void> resume();

  /// 녹음을 끝내고 파일 경로를 돌려준다.
  Future<String?> stop();

  /// 앱 전용 임시 폴더 안의 새 `.m4a` 경로.
  Future<String> newFilePath();

  /// 교체되는 이전 녹음 파일을 지운다. 새 녹음이 시작된 뒤에만 호출한다.
  Future<void> discard(String path);

  /// 통화·알람 등 다른 오디오 사용으로 인한 인터럽트.
  Stream<InterruptionKind> get interruptions;

  /// 0..1로 정규화한 입력 음량(파형 표시용).
  Stream<double> get levels;

  Future<void> dispose();
}

/// 경과 시간을 재는 시계. 가짜로 바꿔 150·180초를 즉시 만든다.
abstract interface class RecordingClock {
  DateTime now();

  Stream<void> ticks(Duration interval);
}

abstract interface class PreviewPlayerPort {
  Future<void> load(String path);

  Future<void> play();

  Future<void> pause();

  Future<void> seek(Duration position);

  Stream<Duration> get position;

  Stream<Duration?> get duration;

  Stream<bool> get playing;

  /// 끝까지 재생했는지.
  Stream<bool> get completed;

  Future<void> dispose();
}

abstract interface class PhoneLauncher {
  /// `tel:` 실행. 실행하지 못하면 false.
  Future<bool> call(String phone);
}
