import 'package:flutter_tts/flutter_tts.dart';
import 'package:ieum_mobile/core/voice/tts_service.dart';

/// 기기에 내장된 TTS 엔진(flutter_tts). 호출 요금이 없다.
class OnDeviceTtsService implements TtsService {
  OnDeviceTtsService([FlutterTts? tts]) : _tts = tts ?? FlutterTts();

  static const String _locale = 'ko-KR';

  /// flutter_tts의 기본 속도(0.5)가 보통 속도다. 배속은 이 값에 곱한다.
  static const double _baseRate = 0.5;

  final FlutterTts _tts;

  @override
  Future<bool> initialize() async {
    try {
      await _tts.awaitSpeakCompletion(true);
      final dynamic available = await _tts.isLanguageAvailable(_locale);
      if (available != true) return false;
      await _tts.setLanguage(_locale);
      return true;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal}) async {
    try {
      await _tts.setSpeechRate(_baseRate * speechRate);
      await _tts.speak(text);
    } catch (_) {
      // 엔진 오류로 앱이 죽지 않게 한다. 화면은 읽기가 끝난 것으로 보고 버튼을 되돌린다.
    }
  }

  @override
  Future<void> stop() async {
    try {
      await _tts.stop();
    } catch (_) {}
  }
}
