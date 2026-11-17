package com.ieum.backend.domain.auth.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 네이버는 토큰이 어느 앱에 발급됐는지 확인하는 API가 없다. 프로필 조회 성공으로 토큰 유효성만 검증한다.
 * (한계는 PR 본문에 명시)
 */
@Component
public class NaverTokenVerifier extends RestSocialTokenVerifier {

    private static final String BASE_URL = "https://openapi.naver.com";

    private final RestClient restClient;

    public NaverTokenVerifier(RestClient.Builder socialRestClientBuilder) {
        this.restClient = socialRestClientBuilder.clone().baseUrl(BASE_URL).build();
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.NAVER;
    }

    @Override
    public SocialProfile verify(String token) {
        JsonNode body = call(() -> restClient.get().uri("/v1/nid/me")
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .body(JsonNode.class));
        if (body == null || !"00".equals(body.path("resultcode").asText())) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        JsonNode response = body.path("response");
        String subject = response.path("id").asText("");
        if (subject.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return new SocialProfile(subject, textOrNull(response.path("email")), textOrNull(response.path("name")));
    }
}
