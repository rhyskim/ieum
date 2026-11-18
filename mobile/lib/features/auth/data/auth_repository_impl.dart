import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/auth/auth_token_store.dart';
import 'package:ieum_mobile/core/auth/token_pair.dart';
import 'package:ieum_mobile/core/config/social_keys.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/core/push/push_service.dart';
import 'package:ieum_mobile/features/auth/data/sdk_social_token_source.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'auth_repository_impl.g.dart';

class AuthRepositoryImpl implements AuthRepository {
  const AuthRepositoryImpl(this._dio, this._store, [this._readFcmToken]);

  final Dio _dio;
  final AuthTokenStore _store;

  /// 로그아웃 시 이 기기의 푸시 토큰을 서버에서 해제하기 위해 읽는다. 없거나 실패하면 생략한다.
  final Future<String?> Function()? _readFcmToken;

  @override
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel}) =>
      _authenticate('/api/v1/auth/${provider.pathSegment}', socialToken, deviceLabel);

  @override
  Future<void> restore(SocialProvider provider, String socialToken, {String? deviceLabel}) =>
      _authenticate('/api/v1/auth/${provider.pathSegment}/restore', socialToken, deviceLabel);

  @override
  Future<void> withdraw(ContentChoice choice) async {
    await _send(
      () => _dio.delete<Map<String, dynamic>>(
        '/api/v1/users/me',
        data: <String, String>{'contentChoice': choice.apiValue},
      ),
    );
    await _store.clear();
  }

  @override
  Future<List<LinkedIdentity>> fetchIdentities() async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>('/api/v1/auth/identities'),
    );
    return (data! as List<dynamic>)
        .map((dynamic e) => LinkedIdentity.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> linkIdentity(SocialProvider provider, String socialToken) async {
    await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/identities/${provider.pathSegment}',
        data: <String, String>{'token': socialToken},
      ),
    );
  }

  @override
  Future<void> unlinkIdentity(SocialProvider provider) async {
    await _send(
      () => _dio.delete<Map<String, dynamic>>('/api/v1/auth/identities/${provider.pathSegment}'),
    );
  }

  /// 로그인·복구는 같은 요청 본문과 응답(토큰)을 쓴다. 신규 가입이면 true.
  Future<bool> _authenticate(String path, String socialToken, String? deviceLabel) async {
    final Map<String, dynamic> data = await _send(
      () => _dio.post<Map<String, dynamic>>(
        path,
        data: <String, dynamic>{
          'token': socialToken,
          'deviceLabel': deviceLabel ?? _defaultDeviceLabel(),
        },
      ),
    ) as Map<String, dynamic>;
    await _store.save(
      TokenPair(
        accessToken: data['accessToken'] as String,
        refreshToken: data['refreshToken'] as String,
      ),
    );
    return data['newUser'] as bool;
  }

  @override
  Future<void> logout() async {
    try {
      final String? refreshToken = await _store.readRefreshToken();
      if (refreshToken != null) {
        final String? fcmToken = await _safeFcmToken();
        await _send(
          () => _dio.post<Map<String, dynamic>>(
            '/api/v1/auth/logout',
            data: <String, String>{
              'refreshToken': refreshToken,
              if (fcmToken != null) 'fcmToken': fcmToken,
            },
          ),
        );
      }
    } on ApiException {
      // 네트워크가 끊겼어도 이 기기에서는 로그아웃돼야 한다. 서버 세션은 만료로 정리된다.
    } finally {
      await _store.clear();
    }
  }

  Future<String?> _safeFcmToken() async {
    try {
      return await _readFcmToken?.call();
    } on Object {
      return null;
    }
  }

  @override
  Future<List<DeviceSession>> fetchSessions() async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>('/api/v1/auth/sessions'),
    );
    return (data! as List<dynamic>)
        .map((dynamic e) => DeviceSession.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> revokeSession(String sessionId) async {
    await _send(() => _dio.delete<Map<String, dynamic>>('/api/v1/auth/sessions/$sessionId'));
  }

  /// 공통 응답 봉투(`ApiResponse`)에서 data를 꺼낸다. data가 없는 응답(로그아웃 등)은 null.
  Future<Object?> _send(Future<Response<Map<String, dynamic>>> Function() request) async {
    try {
      final Response<Map<String, dynamic>> response = await request();
      return response.data?['data'];
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }

  String _defaultDeviceLabel() => switch (defaultTargetPlatform) {
        TargetPlatform.iOS => 'iOS 기기',
        TargetPlatform.android => 'Android 기기',
        _ => '알 수 없는 기기',
      };
}

@riverpod
AuthRepository authRepository(Ref ref) => AuthRepositoryImpl(
      ref.watch(dioProvider),
      ref.watch(authTokenStoreProvider),
      ref.watch(pushServiceProvider).getToken,
    );

@riverpod
SocialTokenSource socialTokenSource(Ref ref) =>
    SdkSocialTokenSource(const SocialKeys.fromEnvironment());
