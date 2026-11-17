import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/config/app_env.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/api_response.dart';

void main() {
  group('AppEnv.resolveApiBaseUrl', () {
    test('Android 에뮬레이터 기본 URL은 http://10.0.2.2:8080 이다', () {
      final url = AppEnv.resolveApiBaseUrl(
        definedUrl: '',
        platform: TargetPlatform.android,
        isRelease: false,
      );
      expect(url, 'http://10.0.2.2:8080');
    });

    test('iOS 시뮬레이터 및 호스트 기본 URL은 http://localhost:8080 이다', () {
      final url = AppEnv.resolveApiBaseUrl(
        definedUrl: '',
        platform: TargetPlatform.iOS,
        isRelease: false,
      );
      expect(url, 'http://localhost:8080');
    });

    test('Release 빌드에서 https:// 가 아닌 URL을 사용하면 StateError 를 던진다', () {
      expect(
        () => AppEnv.resolveApiBaseUrl(
          definedUrl: 'http://api.ieum.app',
          platform: TargetPlatform.android,
          isRelease: true,
        ),
        throwsStateError,
      );
    });
  });

  group('ApiResponse.unwrapOrThrow & ApiException.fromDioException', () {
    test('code 가 SUCCESS 가 아니면 ApiException 을 던진다', () {
      final response = ApiResponse<String>(
        code: 'FEATURE_NOT_ENTITLED',
        message: '현재 요금제에서 사용할 수 없는 기능입니다.',
        data: null,
        timestamp: DateTime.utc(2026, 11, 2),
      );

      expect(
        () => response.unwrapOrThrow(statusCode: 403),
        throwsA(
          isA<ApiException>()
              .having((e) => e.code, 'code', 'FEATURE_NOT_ENTITLED')
              .having((e) => e.statusCode, 'statusCode', 403),
        ),
      );
    });

    test('code 가 SUCCESS 이더라도 data 가 null 이면 EMPTY_RESPONSE_DATA 예외를 던진다', () {
      final response = ApiResponse<String>(
        code: 'SUCCESS',
        message: '성공',
        data: null,
        timestamp: DateTime.utc(2026, 11, 2),
      );

      expect(
        () => response.unwrapOrThrow(statusCode: 200),
        throwsA(
          isA<ApiException>().having((e) => e.code, 'code', 'EMPTY_RESPONSE_DATA'),
        ),
      );
    });

    test('에러 응답의 data(restorableUntil)를 ApiException이 보존한다', () {
      final req = RequestOptions(path: '/api/v1/auth/kakao');
      final ex = ApiException.fromDioException(
        DioException(
          requestOptions: req,
          type: DioExceptionType.badResponse,
          response: Response<dynamic>(
            requestOptions: req,
            statusCode: 403,
            data: <String, dynamic>{
              'code': 'ACCOUNT_PENDING_DELETION',
              'message': '탈퇴 처리 중인 계정입니다.',
              'data': <String, dynamic>{'restorableUntil': '2026-12-17T00:00:00Z'},
            },
          ),
        ),
      );
      expect(ex.code, 'ACCOUNT_PENDING_DELETION');
      expect(ex.data?['restorableUntil'], '2026-12-17T00:00:00Z');
      expect(ex.restorableUntil, DateTime.utc(2026, 12, 17));
    });

    test('data가 없거나 객체가 아니면 data는 null이고 restorableUntil도 null이다', () {
      final req = RequestOptions(path: '/x');
      final ex = ApiException.fromDioException(
        DioException(
          requestOptions: req,
          type: DioExceptionType.badResponse,
          response: Response<dynamic>(
            requestOptions: req,
            statusCode: 409,
            data: <String, dynamic>{'code': 'GROUP_FULL', 'message': '가득 참', 'data': null},
          ),
        ),
      );
      expect(ex.data, isNull);
      expect(ex.restorableUntil, isNull);
    });

    test('DioException(connectionTimeout / connectionError / 502 non-JSON) 을 한글 메시지로 매핑한다', () {
      final req = RequestOptions(path: '/api/v1/health');

      final timeoutEx = ApiException.fromDioException(
        DioException(requestOptions: req, type: DioExceptionType.connectionTimeout),
      );
      expect(timeoutEx.code, 'NETWORK_TIMEOUT');

      final connErrorEx = ApiException.fromDioException(
        DioException(requestOptions: req, type: DioExceptionType.connectionError),
      );
      expect(connErrorEx.code, 'NETWORK_CONNECTION_ERROR');

      final badGatewayEx = ApiException.fromDioException(
        DioException(
          requestOptions: req,
          type: DioExceptionType.badResponse,
          response: Response<dynamic>(
            requestOptions: req,
            statusCode: 502,
            data: '<html>Bad Gateway</html>',
          ),
        ),
      );
      expect(badGatewayEx.code, 'HTTP_502');
      expect(badGatewayEx.statusCode, 502);
    });
  });
}
