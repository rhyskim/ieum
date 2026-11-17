package com.ieum.backend.domain.auth.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** REST 호출 기반 검증기의 공통 오류 매핑. 4xx는 토큰 문제(401), 그 외 호출 실패는 제공자 장애(503). */
abstract class RestSocialTokenVerifier implements SocialTokenVerifier {

    protected <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        } catch (RestClientException e) {
            // 5xx, 타임아웃, 연결 실패. 요청에 토큰이 들어 있으므로 예외 메시지는 남기지 않는다.
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }

    protected static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}
