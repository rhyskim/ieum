import 'package:freezed_annotation/freezed_annotation.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';

part 'api_response.freezed.dart';
part 'api_response.g.dart';

@Freezed(genericArgumentFactories: true)
abstract class ApiResponse<T> with _$ApiResponse<T> {
  const ApiResponse._();

  const factory ApiResponse({
    required String code,
    required String message,
    T? data,
    required DateTime timestamp,
  }) = _ApiResponse<T>;

  /// Task 2 백엔드 ApiResponse.SUCCESS_CODE = "SUCCESS" 와 1:1 계약 고정
  static const String successCode = 'SUCCESS';

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Object? json) fromJsonT,
  ) => _$ApiResponseFromJson(json, fromJsonT);

  /// code != 'SUCCESS' 이거나 data == null 이면 즉시 ApiException 발생
  T unwrapOrThrow({int? statusCode}) {
    if (code != successCode) {
      throw ApiException(
        code: code,
        message: message,
        statusCode: statusCode,
      );
    }
    final T? payload = data;
    if (payload == null) {
      throw ApiException(
        code: 'EMPTY_RESPONSE_DATA',
        message: '서버 응답 데이터가 비어 있습니다.',
        statusCode: statusCode,
      );
    }
    return payload;
  }
}
