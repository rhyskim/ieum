import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/auth/auth_token_store.dart';
import 'package:ieum_mobile/core/auth/token_pair.dart';
import 'package:ieum_mobile/core/config/app_env.dart';
import 'package:ieum_mobile/core/network/auth_refresh_interceptor.dart';
import 'package:ieum_mobile/core/network/auth_token_reader.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'dio_provider.g.dart';

@Riverpod(keepAlive: true)
Dio dio(Ref ref) {
  final tokenReader = ref.watch(authTokenReaderProvider);
  final tokenStore = ref.watch(authTokenStoreProvider);

  final dio = Dio(
    BaseOptions(
      baseUrl: AppEnv.apiBaseUrl,
      connectTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 10),
      sendTimeout: const Duration(seconds: 10),
      headers: const <String, String>{
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      },
    ),
  );

  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await tokenReader.readAccessToken();
        if (token != null && token.isNotEmpty) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
    ),
  );

  dio.interceptors.add(
    AuthRefreshInterceptor(
      dio: dio,
      store: tokenStore,
      refresh: (refreshToken) => _requestTokenRefresh(refreshToken),
    ),
  );

  if (kDebugMode) {
    dio.interceptors.add(
      LogInterceptor(
        requestBody: true,
        responseBody: true,
      ),
    );
  }

  ref.onDispose(() {
    dio.close(force: true);
  });

  return dio;
}

/// 인터셉터가 없는 별도 Dio로 갱신한다. 같은 Dio를 쓰면 갱신 요청의 401이 다시 갱신을 부르는 재귀가 생긴다.
Future<TokenPair> _requestTokenRefresh(String refreshToken) async {
  final bare = Dio(
    BaseOptions(
      baseUrl: AppEnv.apiBaseUrl,
      connectTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 10),
    ),
  );
  try {
    final response = await bare.post<Map<String, dynamic>>(
      '/api/v1/auth/refresh',
      data: <String, String>{'refreshToken': refreshToken},
    );
    final data = response.data?['data'] as Map<String, dynamic>;
    return TokenPair(
      accessToken: data['accessToken'] as String,
      refreshToken: data['refreshToken'] as String,
    );
  } finally {
    bare.close();
  }
}
