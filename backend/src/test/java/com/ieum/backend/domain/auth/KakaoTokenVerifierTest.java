package com.ieum.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.backend.domain.auth.social.KakaoTokenVerifier;
import com.ieum.backend.domain.auth.social.SocialAuthProperties;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoTokenVerifierTest {

    private MockRestServiceServer server;
    private KakaoTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new KakaoTokenVerifier(builder, new SocialAuthProperties("our-app", null, Duration.ofSeconds(1)));
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("우리 앱(app_id 일치)의 토큰이면 프로필을 돌려준다")
    void validTokenReturnsProfile() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andExpect(header("Authorization", "Bearer t"))
            .andRespond(withSuccess("{\"id\":123,\"app_id\":\"our-app\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
            .andRespond(withSuccess(
                "{\"id\":123,\"kakao_account\":{\"email\":\"a@b.c\",\"profile\":{\"nickname\":\"닉\"}}}",
                MediaType.APPLICATION_JSON));

        SocialProfile profile = verifier.verify("t");

        assertThat(profile.subject()).isEqualTo("123");
        assertThat(profile.email()).isEqualTo("a@b.c");
        assertThat(profile.name()).isEqualTo("닉");
    }

    @Test
    @DisplayName("다른 앱에 발급된 토큰(app_id 불일치)은 INVALID_SOCIAL_TOKEN")
    void tokenFromAnotherAppIsRejected() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withSuccess("{\"id\":123,\"app_id\":\"other-app\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("t"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));
    }

    @Test
    @DisplayName("제공자가 401을 주면 INVALID_SOCIAL_TOKEN, 5xx면 SOCIAL_PROVIDER_UNAVAILABLE")
    void providerErrorsAreMapped() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> verifier.verify("forged"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));

        server.reset();
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withServerError());
        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));
    }

    @Test
    @DisplayName("KAKAO_APP_ID 설정이 비어 있으면 어떤 토큰도 거절한다(app_id 검증을 건너뛰지 않는다)")
    void blankAppIdConfigRejectsEverything() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        KakaoTokenVerifier unconfigured = new KakaoTokenVerifier(builder, new SocialAuthProperties("", null, null));
        s.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withSuccess("{\"id\":1,\"app_id\":\"\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> unconfigured.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN));
    }

    @Test
    @DisplayName("제공자 연결·읽기 타임아웃(I/O 예외)은 SOCIAL_PROVIDER_UNAVAILABLE이며 재시도하지 않는다")
    void timeoutIsProviderUnavailableWithoutRetry() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withException(new IOException("Read timed out")));

        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));
        server.verify(); // expect 1회만 등록했으므로 두 번째 호출이 있었다면 여기 오기 전에 실패한다.
    }

    @Test
    @DisplayName("app_id 확인 뒤 프로필 조회 단계의 장애도 SOCIAL_PROVIDER_UNAVAILABLE")
    void profileStageOutageIsProviderUnavailable() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(withSuccess("{\"id\":123,\"app_id\":\"our-app\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withServerError());

        assertThatThrownBy(() -> verifier.verify("t"))
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));
    }
}
