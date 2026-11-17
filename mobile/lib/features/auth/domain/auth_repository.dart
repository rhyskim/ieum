import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

abstract interface class AuthRepository {
  /// 소셜 토큰을 서버에 보내 로그인하고 토큰을 저장한다. 신규 가입이면 true.
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel});

  /// 현재 기기 로그아웃. 서버 호출이 실패해도 로컬 토큰은 반드시 지운다.
  Future<void> logout();

  Future<List<DeviceSession>> fetchSessions();

  Future<void> revokeSession(String sessionId);
}

/// 카카오·네이버·Apple SDK로 소셜 토큰을 받아 오는 부분. SDK 연동 전까지는 [UnconfiguredSocialTokenSource].
abstract interface class SocialTokenSource {
  Future<String> obtainToken(SocialProvider provider);
}
