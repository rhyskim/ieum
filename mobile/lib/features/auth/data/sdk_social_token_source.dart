import 'package:flutter/services.dart' show PlatformException;
import 'package:ieum_mobile/core/config/social_keys.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';
import 'package:naver_login_sdk/naver_login_sdk.dart';

const ApiException _notConfigured = ApiException(
  code: 'SOCIAL_SDK_NOT_CONFIGURED',
  message: '소셜 로그인 설정이 아직 연결되지 않았습니다.',
);

ApiException _sdkError(String name) =>
    ApiException(code: 'SOCIAL_SDK_ERROR', message: '$name 로그인을 완료하지 못했어요. 다시 시도해 주세요.');

/// 카카오 SDK가 사용자 취소를 알리는 방식(`ClientErrorCause.cancelled`, 또는 `PlatformException` CANCELED)인지 판별한다.
bool isKakaoCancel(Object error) {
  if (error is KakaoClientException) return error.reason == ClientErrorCause.cancelled;
  if (error is PlatformException) return error.code == 'CANCELED';
  return false;
}

/// 네이버 SDK는 취소를 `onError(message: user_cancel)`로 알린다.
bool isNaverCancel(String? message) => message == 'user_cancel';

/// 카카오·네이버 SDK로 토큰을 받는다. 키가 없는 제공자와 Apple(범위 밖)은 `SOCIAL_SDK_NOT_CONFIGURED`.
/// 이 클래스는 SDK를 직접 호출하므로 실기기 확인이 필요하다. 취소 판별은 위 함수로 분리해 테스트한다.
class SdkSocialTokenSource implements SocialTokenSource {
  SdkSocialTokenSource(this._keys);

  final SocialKeys _keys;
  bool _kakaoInitialized = false;
  bool _naverInitialized = false;

  @override
  Future<String> obtainToken(SocialProvider provider, {bool forceRefresh = false}) async {
    return await switch (provider) {
      SocialProvider.kakao => _kakao(forceRefresh),
      SocialProvider.naver => _naver(forceRefresh),
      SocialProvider.apple => throw _notConfigured,
    };
  }

  Future<String> _kakao(bool forceRefresh) async {
    if (!_keys.kakaoConfigured) throw _notConfigured;
    if (!_kakaoInitialized) {
      KakaoSdk.init(nativeAppKey: _keys.kakaoNativeAppKey);
      _kakaoInitialized = true;
    }
    try {
      // 재발급 요청이면 저장된 토큰을 버리고 새로 받는다.
      if (forceRefresh) await TokenManagerProvider.instance.manager.clear();
      final OAuthToken token = await isKakaoTalkInstalled()
          ? await UserApi.instance.loginWithKakaoTalk()
          : await UserApi.instance.loginWithKakaoAccount();
      return token.accessToken;
    } on Object catch (e) {
      if (isKakaoCancel(e)) throw const SocialLoginCancelled();
      // 카카오톡 로그인 중 취소는 PlatformException으로 올 수 있으나 위에서 처리했다. 그 밖은 계정 로그인으로 한 번 더 시도하지 않는다.
      throw _sdkError('카카오');
    }
  }

  Future<String> _naver(bool forceRefresh) async {
    if (!_keys.naverConfigured) throw _notConfigured;
    if (!_naverInitialized) {
      await NaverLoginSDK.initialize(
        urlScheme: _keys.naverUrlScheme.isEmpty ? null : _keys.naverUrlScheme,
        clientId: _keys.naverClientId,
        clientSecret: _keys.naverClientSecret,
        clientName: _keys.naverClientName,
      );
      _naverInitialized = true;
    }
    if (forceRefresh) await NaverLoginSDK.logout(isForced: true);
    bool cancelled = false;
    final bool ok = await NaverLoginSDK.login(
      callback: OAuthLoginCallback(
        onSuccess: () {},
        onError: (int code, String message) => cancelled = isNaverCancel(message),
      ),
    );
    if (!ok) {
      if (cancelled) throw const SocialLoginCancelled();
      throw _sdkError('네이버');
    }
    final String token = await NaverLoginSDK.getAccessToken();
    if (token.isEmpty) throw _sdkError('네이버');
    return token;
  }
}
