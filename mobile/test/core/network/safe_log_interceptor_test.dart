import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/network/safe_log_interceptor.dart';

class _SecretAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody.fromString(
      jsonEncode(<String, dynamic>{
        'code': 'SUCCESS',
        'data': <String, dynamic>{
          'accessToken': 'SECRET-ACCESS',
          'refreshToken': 'SECRET-REFRESH',
        },
      }),
      200,
      headers: <String, List<String>>{
        Headers.contentTypeHeader: <String>[Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  test('로그에는 메서드·URL·상태코드만 남고 토큰(본문·Authorization 헤더)은 남지 않는다', () async {
    final List<String> lines = <String>[];
    final Dio dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'))
      ..httpClientAdapter = _SecretAdapter()
      ..interceptors.add(SafeLogInterceptor(log: lines.add));

    await dio.post<dynamic>(
      '/api/v1/auth/kakao',
      data: <String, String>{'token': 'SECRET-SOCIAL', 'refreshToken': 'SECRET-REFRESH-REQ'},
      options: Options(headers: <String, String>{'Authorization': 'Bearer SECRET-BEARER'}),
    );

    final String all = lines.join('\n');
    expect(all, contains('POST'));
    expect(all, contains('/api/v1/auth/kakao'));
    expect(all, contains('200'));
    expect(all, isNot(contains('SECRET')));
  });

  test('실패 응답도 상태코드만 기록하고 본문은 기록하지 않는다', () async {
    final List<String> lines = <String>[];
    final Dio dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'))
      ..httpClientAdapter = _FailingAdapter()
      ..interceptors.add(SafeLogInterceptor(log: lines.add));

    await expectLater(dio.get<dynamic>('/api/v1/auth/sessions'), throwsA(isA<DioException>()));

    final String all = lines.join('\n');
    expect(all, contains('401'));
    expect(all, isNot(contains('SECRET')));
  });
}

class _FailingAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody.fromString(
      jsonEncode(<String, dynamic>{'code': 'TOKEN_EXPIRED', 'message': 'SECRET-MESSAGE'}),
      401,
      headers: <String, List<String>>{
        Headers.contentTypeHeader: <String>[Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
