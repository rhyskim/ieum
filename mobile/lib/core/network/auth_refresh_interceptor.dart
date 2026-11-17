import 'dart:async';

import 'package:dio/dio.dart';
import 'package:ieum_mobile/core/auth/auth_token_store.dart';
import 'package:ieum_mobile/core/auth/token_pair.dart';

typedef RefreshTokenCall = Future<TokenPair> Function(String refreshToken);

/// 401 `TOKEN_EXPIRED` 를 받으면 Refresh로 토큰을 갱신하고 원 요청을 1회 재시도한다.
///
/// - 동시에 여러 요청이 만료되어도 갱신은 하나만 보내고(single-flight) 나머지는 그 결과를 기다린다.
///   Refresh는 Rotation이라 같은 토큰을 두 번 쓰면 서버가 탈취로 의심할 수 있기 때문이다.
/// - 이미 갱신된 뒤에 이전 토큰으로 보낸 요청의 401이 늦게 도착하면 다시 갱신하지 않고 새 토큰으로 재시도한다.
class AuthRefreshInterceptor extends Interceptor {
  AuthRefreshInterceptor({
    required Dio dio,
    required AuthTokenStore store,
    required RefreshTokenCall refresh,
  })  : _dio = dio,
        _store = store,
        _refresh = refresh;

  static const String _retriedKey = 'auth_refresh_retried';

  final Dio _dio;
  final AuthTokenStore _store;
  final RefreshTokenCall _refresh;

  Future<String?>? _inFlight;

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    if (!_isExpiredAccessToken(err)) {
      handler.next(err);
      return;
    }

    final String? sent = err.requestOptions.headers['Authorization'] as String?;
    final String? current = await _store.readAccessToken();
    final String? token;
    if (current != null && sent != 'Bearer $current') {
      token = current; // 다른 요청이 이미 갱신을 끝냈다.
    } else {
      token = await _refreshOnce();
    }

    if (token == null) {
      handler.next(err);
      return;
    }

    try {
      final RequestOptions options = err.requestOptions;
      options.headers['Authorization'] = 'Bearer $token';
      options.extra[_retriedKey] = true;
      handler.resolve(await _dio.fetch<dynamic>(options));
    } on DioException catch (e) {
      handler.next(e);
    }
  }

  bool _isExpiredAccessToken(DioException err) {
    if (err.response?.statusCode != 401) return false;
    if (err.requestOptions.extra[_retriedKey] == true) return false;
    final Object? body = err.response?.data;
    return body is Map<String, dynamic> && body['code'] == 'TOKEN_EXPIRED';
  }

  Future<String?> _refreshOnce() {
    return _inFlight ??= _doRefresh().whenComplete(() => _inFlight = null);
  }

  Future<String?> _doRefresh() async {
    final String? refreshToken = await _store.readRefreshToken();
    if (refreshToken == null) {
      await _store.clear();
      return null;
    }
    try {
      final TokenPair pair = await _refresh(refreshToken);
      await _store.save(pair);
      return pair.accessToken;
    } on Object {
      // Refresh도 거부됐다(만료·폐기·재사용 탐지). 로컬 세션을 정리해 다시 로그인하게 한다.
      await _store.clear();
      return null;
    }
  }
}
