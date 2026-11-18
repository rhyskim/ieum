import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/config/social_keys.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/data/sdk_social_token_source.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';

void main() {
  group('취소 판별', () {
    test('카카오: ClientErrorCause.cancelled와 CANCELED 코드만 취소로 본다', () {
      expect(isKakaoCancel(KakaoClientException(ClientErrorCause.cancelled, 'x')), isTrue);
      expect(isKakaoCancel(PlatformException(code: 'CANCELED')), isTrue);
      expect(isKakaoCancel(KakaoClientException(ClientErrorCause.unknown, 'x')), isFalse);
      expect(isKakaoCancel(StateError('x')), isFalse);
    });

    test('네이버: user_cancel만 취소로 본다', () {
      expect(isNaverCancel('user_cancel'), isTrue);
      expect(isNaverCancel('naverapp_not_installed'), isFalse);
      expect(isNaverCancel(null), isFalse);
    });
  });

  group('키가 없을 때', () {
    test('SocialKeys: 값이 비면 설정 안 됨, 네이버는 id·secret이 모두 있어야 설정됨', () {
      expect(const SocialKeys().kakaoConfigured, isFalse);
      expect(const SocialKeys(naverClientId: 'id').naverConfigured, isFalse);
      expect(const SocialKeys(naverClientId: 'id', naverClientSecret: 's').naverConfigured, isTrue);
    });

    test('모든 제공자가 SDK를 건드리지 않고 SOCIAL_SDK_NOT_CONFIGURED를 던진다', () async {
      final SdkSocialTokenSource source = SdkSocialTokenSource(const SocialKeys());
      for (final SocialProvider p in SocialProvider.values) {
        await expectLater(
          source.obtainToken(p),
          throwsA(isA<ApiException>().having((e) => e.code, 'code', 'SOCIAL_SDK_NOT_CONFIGURED')),
        );
      }
    });
  });
}
