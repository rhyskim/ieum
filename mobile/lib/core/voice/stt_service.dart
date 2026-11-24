import 'package:ieum_mobile/core/voice/stt_recognition_result.dart';

/// 음성 인식을 시작할 수 있는 상태인지. 거부·미지원을 화면이 각각 다른 안내로 풀어 쓴다.
enum SttAvailability { ready, unsupported, permissionDenied, permissionPermanentlyDenied }

/// 인식 도중 발생한 오류. 스트림 오류로 전달되며 앱을 죽이지 않고 화면이 안내 문구로 바꾼다.
class SttException implements Exception {
  const SttException(this.message);
  final String message;

  @override
  String toString() => 'SttException($message)';
}

abstract interface class SttService {
  Future<SttAvailability> initialize();
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  });
  Future<void> stopListening();
}

class NoopSttService implements SttService {
  const NoopSttService();

  @override
  Future<SttAvailability> initialize() async => SttAvailability.unsupported;

  @override
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  }) => const Stream<SttRecognitionResult>.empty();

  @override
  Future<void> stopListening() async {}
}
