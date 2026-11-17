import 'package:flutter/foundation.dart';

abstract final class AppEnv {
  static const String _definedApiBaseUrl = String.fromEnvironment('API_BASE_URL');

  static String get apiBaseUrl {
    return resolveApiBaseUrl(
      definedUrl: _definedApiBaseUrl,
      platform: defaultTargetPlatform,
      isRelease: kReleaseMode,
    );
  }

  @visibleForTesting
  static String resolveApiBaseUrl({
    required String definedUrl,
    required TargetPlatform platform,
    required bool isRelease,
  }) {
    final String resolved = definedUrl.trim().isNotEmpty
        ? definedUrl.trim()
        : (platform == TargetPlatform.android
              ? 'http://10.0.2.2:8080'
              : 'http://localhost:8080');

    if (isRelease && !resolved.startsWith('https://')) {
      throw StateError(
        'Release 빌드에서는 반드시 HTTPS API_BASE_URL을 사용해야 합니다: $resolved',
      );
    }
    return resolved;
  }
}
