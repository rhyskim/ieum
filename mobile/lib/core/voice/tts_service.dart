abstract final class TtsSpeechRate {
  static const double normal = 1.0;
  static const double slow = 0.85;
}

abstract interface class TtsService {
  /// 한국어 음성을 쓸 수 있으면 true. false면 듣기 기능을 끄고 이유를 안내한다.
  Future<bool> initialize();

  /// 읽기가 끝나거나 [stop]으로 멈출 때까지 기다린다.
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal});
  Future<void> stop();
}

class NoopTtsService implements TtsService {
  const NoopTtsService();

  @override
  Future<bool> initialize() async => false;

  @override
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal}) async {}

  @override
  Future<void> stop() async {}
}
