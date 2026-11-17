abstract final class TtsSpeechRate {
  static const double normal = 1.0;
  static const double slow = 0.85;
}

abstract interface class TtsService {
  Future<void> initialize();
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal});
  Future<void> stop();
}

class NoopTtsService implements TtsService {
  const NoopTtsService();

  @override
  Future<void> initialize() async {}

  @override
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal}) async {}

  @override
  Future<void> stop() async {}
}
