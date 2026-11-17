package com.ieum.backend.domain.auth.social;

import com.ieum.backend.domain.common.enums.OAuthProvider;

public interface SocialTokenVerifier {

    OAuthProvider provider();

    /**
     * 제공자 서버에 직접 확인해 우리 앱용 토큰인지 검증한다.
     *
     * @throws com.ieum.backend.global.exception.BusinessException 위조·만료·다른 앱 토큰은 INVALID_SOCIAL_TOKEN(401),
     *                                                             제공자 장애·타임아웃은 SOCIAL_PROVIDER_UNAVAILABLE(503)
     */
    SocialProfile verify(String token);
}
