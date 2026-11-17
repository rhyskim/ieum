import 'package:dio/dio.dart';

class ApiException implements Exception {
  const ApiException({
    required this.code,
    required this.message,
    this.statusCode,
    this.data,
  });

  final String code;
  final String message;
  final int? statusCode;

  /// 에러 응답 봉투의 `data`(예: `ACCOUNT_PENDING_DELETION`의 `restorableUntil`). 없으면 null.
  final Map<String, dynamic>? data;

  /// 탈퇴 유예 계정의 복구 마감 시각. 응답에 없거나 형식이 틀리면 null.
  DateTime? get restorableUntil {
    final Object? raw = data?['restorableUntil'];
    return raw is String ? DateTime.tryParse(raw) : null;
  }

  factory ApiException.fromDioException(DioException error) {
    final int? statusCode = error.response?.statusCode;
    final Object? data = error.response?.data;

    if (data is Map<String, dynamic>) {
      final Object? rawCode = data['code'];
      final Object? rawMessage = data['message'];
      if (rawCode is String && rawMessage is String) {
        final Object? rawData = data['data'];
        return ApiException(
          code: rawCode,
          message: rawMessage,
          statusCode: statusCode,
          data: rawData is Map<String, dynamic> ? rawData : null,
        );
      }
    }

    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return ApiException(
          code: 'NETWORK_TIMEOUT',
          message: '서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.',
          statusCode: statusCode,
        );
      case DioExceptionType.connectionError:
        return ApiException(
          code: 'NETWORK_CONNECTION_ERROR',
          message: '서버에 연결할 수 없습니다. 인터넷 연결이나 서버 상태를 확인해 주세요.',
          statusCode: statusCode,
        );
      case DioExceptionType.badResponse:
        return ApiException(
          code: 'HTTP_${statusCode ?? 'ERROR'}',
          message: '서버 통신 중 오류가 발생했습니다. (상태 코드: ${statusCode ?? '알 수 없음'})',
          statusCode: statusCode,
        );
      case DioExceptionType.cancel:
        return ApiException(
          code: 'REQUEST_CANCELLED',
          message: '요청이 취소되었습니다.',
          statusCode: statusCode,
        );
      case DioExceptionType.badCertificate:
      case DioExceptionType.unknown:
        return ApiException(
          code: 'UNKNOWN_NETWORK_ERROR',
          message: '일시적인 통신 오류가 발생했습니다. 다시 시도해 주세요.',
          statusCode: statusCode,
        );
    }
  }

  @override
  String toString() => 'ApiException(code: $code, statusCode: $statusCode, message: $message)';
}
