package com.ieum.backend.domain.auth.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoTokenVerifier extends RestSocialTokenVerifier {

    private static final String BASE_URL = "https://kapi.kakao.com";

    private final RestClient restClient;
    private final String expectedAppId;

    public KakaoTokenVerifier(RestClient.Builder socialRestClientBuilder, SocialAuthProperties properties) {
        this.restClient = socialRestClientBuilder.clone().baseUrl(BASE_URL).build();
        this.expectedAppId = properties.kakaoAppId();
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public SocialProfile verify(String token) {
        // 1) 이 토큰이 우리 앱에 발급된 것인지 확인한다(다른 앱 토큰 재사용 방지).
        JsonNode info = call(() -> get("/v1/user/access_token_info", token));
        if (expectedAppId == null || expectedAppId.isBlank()
            || !expectedAppId.equals(info.path("app_id").asText())) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        // 2) 프로필 조회
        JsonNode me = call(() -> get("/v2/user/me", token));
        String subject = me.path("id").asText("");
        if (subject.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return new SocialProfile(
            subject,
            textOrNull(me.path("kakao_account").path("email")),
            textOrNull(me.path("kakao_account").path("profile").path("nickname"))
        );
    }

    private JsonNode get(String path, String token) {
        JsonNode body = restClient.get().uri(path)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .body(JsonNode.class);
        if (body == null) {
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
        return body;
    }
}
