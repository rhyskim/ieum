import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'auth_token_reader.g.dart';

abstract interface class AuthTokenReader {
  Future<String?> readAccessToken();
}

class NoopAuthTokenReader implements AuthTokenReader {
  const NoopAuthTokenReader();

  @override
  Future<String?> readAccessToken() async => null;
}

@Riverpod(keepAlive: true)
AuthTokenReader authTokenReader(Ref ref) => const NoopAuthTokenReader();
