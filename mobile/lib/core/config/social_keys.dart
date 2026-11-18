/// 소셜 로그인 SDK 키. 커밋하지 않고 `--dart-define`으로만 주입한다(docs/social-login-keys.md).
/// 값이 비어 있으면 해당 제공자는 "설정 안 됨"으로 취급해 앱이 안내 문구를 보인다.
class SocialKeys {
  const SocialKeys({
    this.kakaoNativeAppKey = '',
    this.naverClientId = '',
    this.naverClientSecret = '',
    this.naverClientName = '이음',
    this.naverUrlScheme = '',
  });

  const SocialKeys.fromEnvironment()
    : kakaoNativeAppKey = const String.fromEnvironment('KAKAO_NATIVE_APP_KEY'),
      naverClientId = const String.fromEnvironment('NAVER_CLIENT_ID'),
      naverClientSecret = const String.fromEnvironment('NAVER_CLIENT_SECRET'),
      naverClientName = const String.fromEnvironment('NAVER_CLIENT_NAME', defaultValue: '이음'),
      naverUrlScheme = const String.fromEnvironment('NAVER_URL_SCHEME');

  final String kakaoNativeAppKey;
  final String naverClientId;
  final String naverClientSecret;
  final String naverClientName;

  /// iOS 전용(Apple 연동과 함께 iOS 출시 때 사용). Android에서는 필요 없다.
  final String naverUrlScheme;

  bool get kakaoConfigured => kakaoNativeAppKey.trim().isNotEmpty;
  bool get naverConfigured =>
      naverClientId.trim().isNotEmpty && naverClientSecret.trim().isNotEmpty;
}
