import 'package:freezed_annotation/freezed_annotation.dart';

part 'stt_recognition_result.freezed.dart';

@freezed
abstract class SttRecognitionResult with _$SttRecognitionResult {
  const factory SttRecognitionResult({
    required String text,
    required bool isFinal,
    double? confidence,
  }) = _SttRecognitionResult;
}
