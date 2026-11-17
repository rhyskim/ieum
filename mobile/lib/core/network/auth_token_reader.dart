import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/auth/auth_token_store.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'auth_token_reader.g.dart';

abstract interface class AuthTokenReader {
  Future<String?> readAccessToken();
}

@Riverpod(keepAlive: true)
AuthTokenReader authTokenReader(Ref ref) => ref.watch(authTokenStoreProvider);
