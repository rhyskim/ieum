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
      final bool ok = await _speech.initialize(onError: _onError);
      if (!ok) return SttAvailability.unsupported;
      final bool hasKorean = (await _speech.locales()).any((l) => l.localeId.replaceAll('-', '_') == _locale);
      return hasKorean ? SttAvailability.ready : SttAvailability.unsupported;
    } catch (_) {
      return SttAvailability.unsupported;
    }
  }

  @override
  Stream<SttRecognitionResult> startListening({Duration maxDuration = const Duration(minutes: 3)}) {
    final StreamController<SttRecognitionResult> controller = StreamController<SttRecognitionResult>();
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
    // 아무 말도 안 했거나 소리를 못 알아들은 경우는 오류가 아니라 빈 종료로 본다.
    if (error.errorMsg != 'error_no_match' && error.errorMsg != 'error_speech_timeout') {
      c.addError(SttException(error.errorMsg));
    }
    c.close();
  }
}
