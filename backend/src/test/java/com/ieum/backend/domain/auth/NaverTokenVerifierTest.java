package com.ieum.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.backend.domain.auth.social.NaverTokenVerifier;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverTokenVerifierTest {

    private static final String ME = "https://openapi.naver.com/v1/nid/me";

    private MockRestServiceServer server;
    private NaverTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new NaverTokenVerifier(builder);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("프로필 조회가 성공하면 subject(id)·이메일·이름을 돌려준다")
    void validTokenReturnsProfile() {
        server.expect(requestTo(ME))
            .andExpect(header("Authorization", "Bearer t"))
            .andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"n-1\",\"email\":\"a@b.c\",\"name\":\"홍\"}}",
                MediaType.APPLICATION_JSON));

        SocialProfile profile = verifier.verify("t");

        assertThat(profile.subject()).isEqualTo("n-1");
        assertThat(profile.email()).isEqualTo("a@b.c");
        assertThat(profile.name()).isEqualTo("홍");
    }

    @Test
    @DisplayName("이메일·이름 동의가 없어도 id만 있으면 가입할 수 있다")
    void profileWithoutOptionalFields() {
        server.expect(requestTo(ME))
            .andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"id\":\"n-2\"}}", MediaType.APPLICATION_JSON));

        SocialProfile profile = verifier.verify("t");

        assertThat(profile.subject()).isEqualTo("n-2");
        assertThat(profile.email()).isNull();
    }

    @Test
    @DisplayName("resultcode가 00이 아니거나 id가 없으면 INVALID_SOCIAL_TOKEN")
    void unsuccessfulBodyIsInvalid() {
        server.expect(requestTo(ME))
            .andRespond(withSuccess("{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));

        server.reset();
        server.expect(requestTo(ME))
            .andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));
    }

    @Test
    @DisplayName("제공자가 401을 주면 INVALID_SOCIAL_TOKEN, 5xx·타임아웃이면 SOCIAL_PROVIDER_UNAVAILABLE")
    void providerErrorsAreMapped() {
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> verifier.verify("forged"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));

        server.reset();
        server.expect(requestTo(ME)).andRespond(withServerError());
        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));

        server.reset();
        server.expect(requestTo(ME)).andRespond(withException(new IOException("Read timed out")));
        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));
    }
}
