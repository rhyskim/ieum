package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.auth.repository.UserIdentityRepository;
import com.ieum.backend.domain.auth.social.AppleTokenVerifier;
import com.ieum.backend.domain.auth.social.KakaoTokenVerifier;
import com.ieum.backend.domain.auth.social.NaverTokenVerifier;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class IdentityLinkIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @SpyBean
    private UserIdentityRepository identityRepository;
    @MockBean
    private KakaoTokenVerifier kakao;
    @MockBean
    private NaverTokenVerifier naver;
    @MockBean
    private AppleTokenVerifier apple;

    // ---------- helpers ----------

    private String stubKakao(String subject) {
        String token = "kakao-" + UUID.randomUUID();
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify(token)).thenReturn(new SocialProfile(subject, null, "카카오유저"));
        return token;
    }

    private String stubNaver(String subject) {
        String token = "naver-" + UUID.randomUUID();
        when(naver.provider()).thenReturn(OAuthProvider.NAVER);
        when(naver.verify(token)).thenReturn(new SocialProfile(subject, null, "네이버유저"));
        return token;
    }

    private String stubApple(String subject) {
        String token = "apple-" + UUID.randomUUID();
        when(apple.provider()).thenReturn(OAuthProvider.APPLE);
        when(apple.verify(token)).thenReturn(new SocialProfile(subject, null, null));
        return token;
    }

    /** 로그인 후 Authorization 헤더 값을 돌려준다. */
    private String loginBearer(String provider, String socialToken) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + socialToken + "\"}"))
            .andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        return "Bearer " + data.path("accessToken").asText();
    }

    private MvcResult link(String bearer, String provider, String socialToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/identities/" + provider)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + socialToken + "\"}"))
            .andReturn();
    }

    private MvcResult unlink(String bearer, String provider) throws Exception {
        return mockMvc.perform(delete("/api/v1/auth/identities/" + provider)
                .header("Authorization", bearer))
            .andReturn();
    }

    private int identityCount(String subject) {
        return jdbc.queryForObject("select count(*) from user_identities where subject = ?", Integer.class, subject);
    }

    private int identityCountOfUserBySubject(String subject) {
        return jdbc.queryForObject(
            "select count(*) from user_identities where user_id = "
                + "(select user_id from user_identities where subject = ?)", Integer.class, subject);
    }

    // ---------- 수용 기준: 계정 연결 ----------

    @Test
    @DisplayName("로그인한 상태에서 새 소셜 수단을 연결하면 목록에 둘 다 보인다")
    void linkNewProviderSucceedsAndListsBoth() throws Exception {
        String kSub = "k-" + UUID.randomUUID();
        String nSub = "n-" + UUID.randomUUID();
        String bearer = loginBearer("kakao", stubKakao(kSub));

        MvcResult r = link(bearer, "naver", stubNaver(nSub));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        assertThat(identityCountOfUserBySubject(kSub)).isEqualTo(2);
        mockMvc.perform(get("/api/v1/auth/identities").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[?(@.provider=='KAKAO')]").exists())
            .andExpect(jsonPath("$.data[?(@.provider=='NAVER')]").exists());
    }

    @Test
    @DisplayName("이미 다른 계정에 가입된 소셜 계정은 병합하지 않고 409로 거절하며 아무것도 바뀌지 않는다")
    void linkingSocialAccountOwnedByAnotherUserIsRejected() throws Exception {
        String mySub = "k-" + UUID.randomUUID();
        String otherNaverSub = "n-" + UUID.randomUUID();
        String myBearer = loginBearer("kakao", stubKakao(mySub));
        loginBearer("naver", stubNaver(otherNaverSub)); // 다른 사용자가 이 네이버 계정으로 가입

        MvcResult r = link(myBearer, "naver", stubNaver(otherNaverSub));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .path("code").asText()).isEqualTo("IDENTITY_ALREADY_REGISTERED");
        assertThat(identityCountOfUserBySubject(mySub)).isEqualTo(1);
        assertThat(identityCountOfUserBySubject(otherNaverSub)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 내 계정에 연결된 소셜 계정을 다시 연결하면 멱등 성공이고 행은 늘지 않는다")
    void linkingAlreadyMyIdentityIsIdempotent() throws Exception {
        String kSub = "k-" + UUID.randomUUID();
        String bearer = loginBearer("kakao", stubKakao(kSub));

        MvcResult r = link(bearer, "kakao", stubKakao(kSub));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(identityCount(kSub)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 제공자의 다른 소셜 계정을 추가로 연결하려 하면 409 (제공자당 1개)")
    void linkingSecondAccountOfSameProviderIsRejected() throws Exception {
        String bearer = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        String otherKakaoSub = "k-" + UUID.randomUUID();

        MvcResult r = link(bearer, "kakao", stubKakao(otherKakaoSub));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(identityCount(otherKakaoSub)).isZero();
    }

    @Test
    @DisplayName("마지막 로그인 수단은 해제할 수 없고(409), 둘 중 하나는 해제할 수 있다")
    void unlinkLastIdentityIsRejected() throws Exception {
        String kSub = "k-" + UUID.randomUUID();
        String bearer = loginBearer("kakao", stubKakao(kSub));

        MvcResult last = unlink(bearer, "kakao");
        assertThat(last.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(last.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .path("code").asText()).isEqualTo("LAST_IDENTITY_CANNOT_UNLINK");
        assertThat(identityCount(kSub)).isEqualTo(1);

        link(bearer, "naver", stubNaver("n-" + UUID.randomUUID()));
        assertThat(unlink(bearer, "naver").getResponse().getStatus()).isEqualTo(200);
        assertThat(identityCount(kSub)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결되지 않은 제공자 해제는 멱등 성공이다(행 변화 없음)")
    void unlinkNotLinkedProviderIsIdempotent() throws Exception {
        String kSub = "k-" + UUID.randomUUID();
        String bearer = loginBearer("kakao", stubKakao(kSub));

        assertThat(unlink(bearer, "apple").getResponse().getStatus()).isEqualTo(200);
        assertThat(identityCount(kSub)).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 없이 호출하면 401이고, 위조 소셜 토큰은 401 INVALID_SOCIAL_TOKEN")
    void requiresAuthAndValidSocialToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/identities")).andExpect(status().isUnauthorized());

        String bearer = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        when(naver.provider()).thenReturn(OAuthProvider.NAVER);
        when(naver.verify("forged")).thenThrow(
            new com.ieum.backend.global.exception.BusinessException(
                com.ieum.backend.global.exception.ErrorCode.INVALID_SOCIAL_TOKEN));
        MvcResult r = link(bearer, "naver", "forged");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- 수용 기준: 동시성 ----------

    @Test
    @DisplayName("같은 소셜 계정을 서로 다른 두 사용자가 동시에 연결하면 행은 1개이고 한쪽만 성공한다")
    void concurrentLinkOfSameSocialAccountCreatesSingleRow() throws Exception {
        String bearerA = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        String bearerB = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        String naverSub = "n-" + UUID.randomUUID();
        String tokenA = stubNaver(naverSub);
        String tokenB = stubNaver(naverSub);

        List<Integer> statuses = runConcurrently(
            () -> link(bearerA, "naver", tokenA).getResponse().getStatus(),
            () -> link(bearerB, "naver", tokenB).getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(identityCount(naverSub)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 요청이 모두 '아직 연결 안 됨'으로 조회한 뒤 동시에 INSERT하면 유니크 제약이 최종 방어선이 되어 행 1개, 한쪽은 409 (Agy 검수 반영)")
    void concurrentLinkReachesUniqueConstraintAsFinalDefense() throws Exception {
        String bearerA = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        String bearerB = loginBearer("kakao", stubKakao("k-" + UUID.randomUUID()));
        String naverSub = "n-" + UUID.randomUUID();
        String tokenA = stubNaver(naverSub);
        String tokenB = stubNaver(naverSub);

        // 로그인까지 끝난 뒤에 건다. 앞의 두 조회는 배리어에서 만나 둘 다 "없음"으로 답하고,
        // 그 뒤의 조회(위반 후 재조회)는 실제 저장소에 위임한다. 인터페이스 spy는 실제 메서드를 못 부르므로
        // 같은 EntityManager로 만든 실제 저장소를 쓴다(AuthApiIntegrationTest와 같은 방식).
        UserIdentityRepository real = new JpaRepositoryFactory(em).getRepository(UserIdentityRepository.class);
        CyclicBarrier afterLookup = new CyclicBarrier(2);
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(inv -> {
            if (lookups.incrementAndGet() <= 2) {
                afterLookup.await(5, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return real.findByProviderAndSubject(inv.getArgument(0), inv.getArgument(1));
        }).when(identityRepository).findByProviderAndSubject(any(), any());

        List<Integer> statuses = runConcurrently(
            () -> link(bearerA, "naver", tokenA).getResponse().getStatus(),
            () -> link(bearerB, "naver", tokenB).getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(identityCount(naverSub)).isEqualTo(1);
        // 사전 조회 2번 + 유니크 위반 뒤 재조회 1번: 최종 방어선 경로를 실제로 지났다.
        assertThat(lookups.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("수단이 2개일 때 서로 다른 수단 동시 해제 2건 → 정확히 1건만 성공하고 항상 1개 이상 남는다")
    void concurrentUnlinkOfDifferentProvidersKeepsAtLeastOne() throws Exception {
        for (int i = 0; i < 5; i++) { // check-then-act 경쟁이 우연히 비켜 가지 않도록 반복한다
            String kSub = "k-" + UUID.randomUUID();
            String bearer = loginBearer("kakao", stubKakao(kSub));
            link(bearer, "naver", stubNaver("n-" + UUID.randomUUID()));
            assertThat(identityCountOfUserBySubject(kSub)).isEqualTo(2);
            UUID userId = jdbc.queryForObject(
                "select user_id from user_identities where subject = ?", UUID.class, kSub);

            List<Integer> statuses = runConcurrently(
                () -> unlink(bearer, "kakao").getResponse().getStatus(),
                () -> unlink(bearer, "naver").getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            Integer remaining = jdbc.queryForObject(
                "select count(*) from user_identities where user_id = ?", Integer.class, userId);
            assertThat(remaining).isEqualTo(1);
        }
    }

    // ---------- util ----------

    @FunctionalInterface
    private interface StatusCall {
        int call() throws Exception;
    }

    private List<Integer> runConcurrently(StatusCall first, StatusCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                barrier.await();
                return first.call();
            });
            Future<Integer> b = pool.submit(() -> {
                barrier.await();
                return second.call();
            });
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
