import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:ieum_mobile/core/auth/token_pair.dart';
import 'package:ieum_mobile/core/network/auth_token_reader.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'auth_token_store.g.dart';

/// 토큰 보관소. 읽기는 [AuthTokenReader]를 그대로 구현해 Dio 인터셉터가 사용한다.
abstract interface class AuthTokenStore implements AuthTokenReader {
  Future<String?> readRefreshToken();

  Future<void> save(TokenPair pair);

  Future<void> clear();
}

/// Keychain(iOS) / Keystore 기반 EncryptedSharedPreferences(Android)에 저장한다.
/// SharedPreferences 같은 평문 저장소에는 토큰을 두지 않는다.
class SecureAuthTokenStore implements AuthTokenStore {
  const SecureAuthTokenStore(this._storage);

  static const String _accessKey = 'ieum.auth.access_token';
  static const String _refreshKey = 'ieum.auth.refresh_token';

  final FlutterSecureStorage _storage;

  @override
  Future<String?> readAccessToken() => _storage.read(key: _accessKey);

  @override
  Future<String?> readRefreshToken() => _storage.read(key: _refreshKey);

  @override
  Future<void> save(TokenPair pair) async {
    await _storage.write(key: _accessKey, value: pair.accessToken);
    await _storage.write(key: _refreshKey, value: pair.refreshToken);
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _accessKey);
    await _storage.delete(key: _refreshKey);
  }
}

@Riverpod(keepAlive: true)
AuthTokenStore authTokenStore(Ref ref) => const SecureAuthTokenStore(FlutterSecureStorage());
