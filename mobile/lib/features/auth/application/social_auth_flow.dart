import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';

/// 소셜 토큰을 받아 [action]에 넘긴다. 로그인·복구·연결 화면이 같이 쓴다.
///
/// - 사용자가 SDK 로그인을 취소하면 오류가 아니므로 [action]을 호출하지 않고 false를 돌려준다.
/// - 서버가 `INVALID_SOCIAL_TOKEN`(만료·위조)을 주면 SDK 토큰을 강제 재발급해 **1회만** 재시도한다.
/// - 그 밖의 오류(제공자 장애 포함)는 재시도하지 않고 그대로 던진다.
Future<bool> runWithSocialToken(
  SocialTokenSource source,
  SocialProvider provider,
  Future<void> Function(String socialToken) action,
) async {
  try {
    final String token = await source.obtainToken(provider);
    try {
      await action(token);
    } on ApiException catch (e) {
      if (e.code != 'INVALID_SOCIAL_TOKEN') rethrow;
      await action(await source.obtainToken(provider, forceRefresh: true));
    }
    return true;
  } on SocialLoginCancelled {
    return false;
  }
}

/// 소셜 로그인 관련 오류를 사용자 안내 문구로 바꾼다.
/// 토큰 문제(401)와 제공자 장애(502/503)는 서로 다른 안내를 준다.
String socialErrorMessage(ApiException e) {
  if (e.code == 'SOCIAL_PROVIDER_UNAVAILABLE' || e.statusCode == 502 || e.statusCode == 503) {
    return '로그인 서비스가 잠시 불안정해요. 잠시 후 다시 시도해 주세요.';
  }
  if (e.code == 'INVALID_SOCIAL_TOKEN') {
    return '로그인 정보를 확인하지 못했어요. 다시 로그인해 주세요.';
  }
  return e.message;
}
