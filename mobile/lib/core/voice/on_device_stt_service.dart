import 'dart:async';

import 'package:ieum_mobile/core/voice/mic_permission.dart';
import 'package:ieum_mobile/core/voice/stt_recognition_result.dart';
import 'package:ieum_mobile/core/voice/stt_service.dart';
import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// 기기에 내장된 음성 인식(speech_to_text). 호출 요금이 없다.
class OnDeviceSttService implements SttService {
  OnDeviceSttService({SpeechToText? speech, MicPermissionService? permission})
      : _speech = speech ?? SpeechToText(),
        _permission = permission ?? const OnDeviceMicPermissionService();

  static const String _locale = 'ko_KR';

  final SpeechToText _speech;
  final MicPermissionService _permission;
  StreamController<SttRecognitionResult>? _active;

  @override
  Future<SttAvailability> initialize() async {
    try {
      switch (await _permission.request()) {
        case MicPermissionStatus.denied:
          return SttAvailability.permissionDenied;
        case MicPermissionStatus.permanentlyDenied:
          return SttAvailability.permissionPermanentlyDenied;
        case MicPermissionStatus.granted:
          break;
      }
      final bool ok = await _speech.initialize(onError: _onError, onStatus: _onStatus);
      if (!ok) return SttAvailability.unsupported;
      // SpeechToText는 앱 전체에서 하나뿐이라 두 번째 initialize는 리스너를 갱신하지 않는다.
      // 서비스 인스턴스가 바뀌어도 현재 인스턴스가 오류·종료를 받도록 매번 다시 건다.
      _speech.errorListener = _onError;
      _speech.statusListener = _onStatus;
      final bool hasKorean = (await _speech.locales()).any((l) => l.localeId.replaceAll('-', '_') == _locale);
      return hasKorean ? SttAvailability.ready : SttAvailability.unsupported;
    } catch (_) {
      return SttAvailability.unsupported;
    }
  }

  @override
  Stream<SttRecognitionResult> startListening({Duration maxDuration = const Duration(minutes: 3)}) {
    final StreamController<SttRecognitionResult> controller = StreamController<SttRecognitionResult>();
    _active?.close();
    _active = controller;
    _speech
        .listen(
          onResult: (SpeechRecognitionResult r) {
            if (controller.isClosed) return;
            controller.add(SttRecognitionResult(
              text: r.recognizedWords,
              isFinal: r.finalResult,
              confidence: r.hasConfidenceRating ? r.confidence : null,
            ));
            if (r.finalResult) controller.close();
          },
          listenOptions: SpeechListenOptions(
            partialResults: true,
            cancelOnError: true,
            listenFor: maxDuration,
            localeId: _locale,
          ),
        )
        .catchError((Object e) {
      if (!controller.isClosed) {
        controller.addError(const SttException('listen_failed'));
        controller.close();
      }
    });
    return controller.stream;
  }

  @override
  Future<void> stopListening() async {
    try {
      await _speech.stop();
    } catch (_) {}
    final StreamController<SttRecognitionResult>? c = _active;
    if (c != null && !c.isClosed) await c.close();
  }

  void _onError(SpeechRecognitionError error) {
    final StreamController<SttRecognitionResult>? c = _active;
    if (c == null || c.isClosed) return;
    c.addError(SttException(error.errorMsg));
    c.close();
  }

  /// 결과 없이 인식이 끝나도(`done`) 화면이 "듣는 중"에 머물지 않도록 스트림을 닫는다.
  void _onStatus(String status) {
    if (status != 'done' && status != 'doneNoResult') return;
    final StreamController<SttRecognitionResult>? c = _active;
    if (c != null && !c.isClosed) c.close();
  }
}
