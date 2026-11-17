import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// 디버그 로그용 인터셉터. 메서드·URL·상태코드만 기록한다.
///
/// 요청·응답 본문과 Authorization 헤더에는 소셜 토큰, Access/Refresh 토큰이 들어 있으므로
/// 절대 기록하지 않는다(`LogInterceptor`의 requestBody/responseBody를 쓰지 않는 이유).
class SafeLogInterceptor extends Interceptor {
  SafeLogInterceptor({void Function(String line)? log}) : _log = log ?? debugPrint;

  final void Function(String line) _log;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    _log('--> ${options.method} ${options.uri}');
    handler.next(options);
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    _log('<-- ${response.statusCode} ${response.requestOptions.method} ${response.requestOptions.uri}');
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    _log('<-- ${err.response?.statusCode ?? err.type.name} '
        '${err.requestOptions.method} ${err.requestOptions.uri}');
    handler.next(err);
  }
}
