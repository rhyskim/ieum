import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/bootstrap/data/health_repository_impl.dart';

class _MockAdapter implements HttpClientAdapter {
  _MockAdapter(this.handler);

  final Future<ResponseBody> Function(RequestOptions options) handler;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => handler(options);

  @override
  void close({bool force = false}) {}
}

void main() {
  group('HealthRepositoryImpl 테스트', () {
    test('GET /api/v1/health 200 OK 응답을 ServerHealth 5개 필드로 정확히 파싱한다', () async {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'));
      dio.httpClientAdapter = _MockAdapter((options) async {
        final payload = jsonEncode(<String, dynamic>{
          'code': 'SUCCESS',
          'message': '요청이 성공적으로 처리되었습니다.',
          'data': <String, dynamic>{
            'status': 'UP',
            'application': 'ieum-backend',
            'profile': 'local',
            'timezone': 'Asia/Seoul',
            'serverTime': '2026-11-02T12:00:00Z',
          },
          'timestamp': '2026-11-02T12:00:00Z',
        });
        return ResponseBody.fromString(
          payload,
          200,
          headers: <String, List<String>>{
            Headers.contentTypeHeader: <String>[Headers.jsonContentType],
          },
        );
      });

      final repo = HealthRepositoryImpl(dio);
      final health = await repo.fetchHealth();

      expect(health.status, 'UP');
      expect(health.application, 'ieum-backend');
      expect(health.profile, 'local');
      expect(health.timezone, 'Asia/Seoul');
      expect(health.serverTime, DateTime.parse('2026-11-02T12:00:00Z'));
    });

    test('서버 500 에러 응답 시 ApiException(INTERNAL_ERROR) 로 매핑된다', () async {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'));
      dio.httpClientAdapter = _MockAdapter((options) async {
        final payload = jsonEncode(<String, dynamic>{
          'code': 'INTERNAL_ERROR',
          'message': '서버 내부 오류가 발생했습니다.',
          'data': null,
          'timestamp': '2026-11-02T12:00:00Z',
        });
        return ResponseBody.fromString(
          payload,
          500,
          headers: <String, List<String>>{
            Headers.contentTypeHeader: <String>[Headers.jsonContentType],
          },
        );
      });

      final repo = HealthRepositoryImpl(dio);
      expect(
        repo.fetchHealth,
        throwsA(
          isA<ApiException>()
              .having((e) => e.code, 'code', 'INTERNAL_ERROR')
              .having((e) => e.statusCode, 'statusCode', 500),
        ),
      );
    });
  });
}
