import 'package:ieum_mobile/core/voice/stt_recognition_result.dart';

abstract interface class SttService {
  Future<bool> initialize();
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  });
  Future<void> stopListening();
}

class NoopSttService implements SttService {
  const NoopSttService();

  @override
  Future<bool> initialize() async => false;

  @override
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  }) => const Stream<SttRecognitionResult>.empty();

  @override
  Future<void> stopListening() async {}
}
