import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/auth/auth_token_store.dart';
import 'package:ieum_mobile/core/auth/token_pair.dart';
import 'package:ieum_mobile/core/network/auth_refresh_interceptor.dart';

class _MemoryTokenStore implements AuthTokenStore {
  _MemoryTokenStore(this._pair);

  TokenPair? _pair;
  int clearCount = 0;

  @override
  Future<String?> readAccessToken() async => _pair?.accessToken;

  @override
  Future<String?> readRefreshToken() async => _pair?.refreshToken;

  @override
  Future<void> save(TokenPair pair) async => _pair = pair;

  @override
  Future<void> clear() async {
    clearCount++;
    _pair = null;
  }
}

/// Authorization 헤더가 `Bearer fresh-access` 일 때만 200, 아니면 401 TOKEN_EXPIRED 를 돌려준다.
class _ServerAdapter implements HttpClientAdapter {
  final List<String> paths = <String>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    paths.add(options.path);
    // 요청들이 서로 겹치도록 응답을 한 틱 늦춘다.
    await Future<void>.delayed(const Duration(milliseconds: 10));
    final bool authorized = options.headers['Authorization'] == 'Bearer fresh-access';
    if (options.path == '/plain-401') {
      return _json(401, <String, dynamic>{'code': 'UNAUTHORIZED', 'message': '인증이 필요합니다.'});
    }
    if (authorized) {
      return _json(200, <String, dynamic>{'code': 'SUCCESS', 'message': 'ok'});
    }
    return _json(401, <String, dynamic>{'code': 'TOKEN_EXPIRED', 'message': '만료'});
  }

  ResponseBody _json(int status, Map<String, dynamic> body) => ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: <String, List<String>>{
          Headers.contentTypeHeader: <String>[Headers.jsonContentType],
        },
      );

  @override
  void close({bool force = false}) {}
}

void main() {
  late _ServerAdapter adapter;
  late _MemoryTokenStore store;
  late Dio dio;
  late int refreshCalls;

  Dio buildDio(Future<TokenPair> Function(String) refresh) {
    final Dio d = Dio(BaseOptions(baseUrl: 'http://localhost:8080'));
    d.httpClientAdapter = adapter;
    d.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          final String? token = await store.readAccessToken();
          if (token != null) {
            options.headers['Authorization'] = 'Bearer $token';
          }
          handler.next(options);
        },
      ),
    );
    d.interceptors.add(AuthRefreshInterceptor(dio: d, store: store, refresh: refresh));
    return d;
  }

  setUp(() {
    adapter = _ServerAdapter();
    store = _MemoryTokenStore(const TokenPair(accessToken: 'old-access', refreshToken: 'old-refresh'));
    refreshCalls = 0;
    dio = buildDio((String refreshToken) async {
      refreshCalls++;
      expect(refreshToken, 'old-refresh');
      await Future<void>.delayed(const Duration(milliseconds: 50));
      return const TokenPair(accessToken: 'fresh-access', refreshToken: 'fresh-refresh');
    });
  });

  test('동시에 401 TOKEN_EXPIRED 3건이 와도 갱신 요청은 1회만 만들고 3건 모두 재시도로 성공한다', () async {
    final List<Response<dynamic>> responses = await Future.wait(<Future<Response<dynamic>>>[
      dio.get<dynamic>('/a'),
      dio.get<dynamic>('/b'),
      dio.get<dynamic>('/c'),
    ]);

    expect(refreshCalls, 1);
    expect(responses.map((r) => r.statusCode), <int?>[200, 200, 200]);
    expect(await store.readAccessToken(), 'fresh-access');
    expect(await store.readRefreshToken(), 'fresh-refresh');
  });

  test('갱신이 끝난 뒤 늦게 도착한 401(이전 토큰으로 보낸 요청)은 다시 갱신하지 않고 새 토큰으로 재시도한다', () async {
    final Future<Response<dynamic>> first = dio.get<dynamic>('/a');
    await first;
    expect(refreshCalls, 1);

    // 이전 토큰으로 이미 전송된 요청이 뒤늦게 401을 받는 상황을 재현한다.
    final RequestOptions stale = RequestOptions(
      path: '/late',
      baseUrl: 'http://localhost:8080',
      headers: <String, dynamic>{'Authorization': 'Bearer old-access'},
    );
    final Response<dynamic> response = await dio.fetch<dynamic>(stale);

    expect(response.statusCode, 200);
    expect(refreshCalls, 1);
  });

  test('갱신에 실패하면 토큰을 지우고 원래의 401 오류를 전달한다', () async {
    refreshCalls = 0;
    final Dio failing = buildDio((String _) async {
      refreshCalls++;
      throw DioException(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        response: Response<dynamic>(
          requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
          statusCode: 401,
        ),
      );
    });

    await expectLater(
      failing.get<dynamic>('/a'),
      throwsA(isA<DioException>().having((e) => e.response?.statusCode, 'status', 401)),
    );
    expect(refreshCalls, 1);
    expect(store.clearCount, 1);
    expect(await store.readAccessToken(), isNull);
  });

  test('TOKEN_EXPIRED 가 아닌 401 은 갱신하지 않는다', () async {
    await expectLater(dio.get<dynamic>('/plain-401'), throwsA(isA<DioException>()));
    expect(refreshCalls, 0);
    expect(adapter.paths, <String>['/plain-401']);
  });
}
