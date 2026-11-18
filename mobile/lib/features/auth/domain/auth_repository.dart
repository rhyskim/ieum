import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

abstract interface class AuthRepository {
  /// 소셜 토큰을 서버에 보내 로그인하고 토큰을 저장한다. 신규 가입이면 true.
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel});

  /// 탈퇴 유예 계정 복구. 소셜 토큰을 다시 검증한 요청에서만 서버가 복구하고 토큰을 저장한다.
  Future<void> restore(SocialProvider provider, String socialToken, {String? deviceLabel});

  /// 탈퇴 요청. 성공하면 서버가 모든 기기 세션을 폐기하므로 로컬 토큰도 지운다. 30일 안에는 복구할 수 있다.
  Future<void> withdraw(ContentChoice choice);

  Future<List<LinkedIdentity>> fetchIdentities();

  /// 로그인된 상태에서 소셜 토큰을 재검증해 새 로그인 수단을 연결한다. 이미 내 것이면 성공(멱등).
  Future<void> linkIdentity(SocialProvider provider, String socialToken);

  Future<void> unlinkIdentity(SocialProvider provider);

  /// 현재 기기 로그아웃. 서버 호출이 실패해도 로컬 토큰은 반드시 지운다.
  Future<void> logout();

  Future<List<DeviceSession>> fetchSessions();

  Future<void> revokeSession(String sessionId);
}

/// 카카오·네이버·Apple SDK로 소셜 토큰을 받아 오는 부분. 키가 없으면 `SOCIAL_SDK_NOT_CONFIGURED`를 던진다.
abstract interface class SocialTokenSource {
  /// 사용자가 SDK 로그인을 취소하면 [SocialLoginCancelled]를 던진다(오류가 아니다).
  /// [forceRefresh]가 true면 캐시된 토큰을 쓰지 않고 새로 발급받는다.
  Future<String> obtainToken(SocialProvider provider, {bool forceRefresh = false});
}

/// 사용자가 소셜 로그인 도중 취소했다. 오류 화면 없이 조용히 현재 화면에 머문다.
class SocialLoginCancelled implements Exception {
  const SocialLoginCancelled();
}
