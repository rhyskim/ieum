import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/entitlement/data/entitlement_repository_impl.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_gate.dart';

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
  group('EntitlementRepositoryImpl & FeatureGate 테스트', () {
    test('GET /api/v1/policies 200 OK 응답을 FeatureEntitlement 및 FeatureGate 로 파싱한다', () async {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'));
      dio.httpClientAdapter = _MockAdapter((options) async {
        final payload = jsonEncode(<String, dynamic>{
          'code': 'SUCCESS',
          'message': '요청이 성공적으로 처리되었습니다.',
          'data': <String, dynamic>{
            'currentPlan': 'FREE',
            'canAttachOriginalVoice': true,
            'maxVoiceDurationSeconds': 180,
            'voiceServerRetentionDays': 30,
            'canDownloadVoiceToLocal': true,
            'sttEngineType': 'ON_DEVICE',
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

      final repo = EntitlementRepositoryImpl(dio);
      final entitlement = await repo.fetchEntitlementPolicy();
      final gate = FeatureGate(entitlement);

      expect(gate.currentPlan, 'FREE');
      expect(gate.canAttachOriginalVoice, isTrue);
      expect(gate.maxVoiceDurationSeconds, 180);
      expect(gate.maxVoiceDuration, const Duration(minutes: 3));
      expect(gate.voiceServerRetentionDays, 30);
      expect(gate.canDownloadVoiceToLocal, isTrue);
      expect(gate.sttEngineType, 'ON_DEVICE');
    });

    test('HTTP 200 이더라도 code != SUCCESS 이면 unwrapOrThrow 가 ApiException 을 던진다', () async {
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
          200,
          headers: <String, List<String>>{
            Headers.contentTypeHeader: <String>[Headers.jsonContentType],
          },
        );
      });

      final repo = EntitlementRepositoryImpl(dio);
      expect(
        repo.fetchEntitlementPolicy,
        throwsA(isA<ApiException>().having((e) => e.code, 'code', 'INTERNAL_ERROR')),
      );
    });
  });
}
