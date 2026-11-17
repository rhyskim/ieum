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
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import com.ieum.backend.global.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JwtProperties jwtProperties;
    @PersistenceContext
    private EntityManager em;

    // 소셜 제공자 호출은 stub으로 대체한다(실제 키 없음).
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
        String token = "kakao-token-" + UUID.randomUUID();
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify(token)).thenReturn(new SocialProfile(subject, subject + "@kakao.test", "카카오유저"));
        return token;
    }

    private JsonNode login(String provider, String socialToken, String deviceLabel) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(objectMapper.writeValueAsString(
                    new LoginBody(socialToken, null, deviceLabel))))
            .andExpect(status().isOk())
            .andReturn();
        return data(r);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andReturn();
    }

    private UUID userIdOf(String provider, String subject) {
        return jdbc.queryForObject(
            "select user_id from user_identities where provider = ? and subject = ?",
            UUID.class, provider, subject);
    }

    private record LoginBody(String token, String name, String deviceLabel) {
    }

    // ---------- 수용 기준 1: 신규 가입 / 기존 로그인 구분 ----------

    @Test
    @DisplayName("유효한 소셜 토큰으로 신규 가입(newUser=true)과 기존 로그인(newUser=false)이 구분된다")
    void signupThenLoginDistinguishesNewUser() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        JsonNode first = login("kakao", stubKakao(subject), "iPhone");
        JsonNode second = login("kakao", stubKakao(subject), "iPhone");

        assertThat(first.path("newUser").asBoolean()).isTrue();
        assertThat(second.path("newUser").asBoolean()).isFalse();
        assertThat(first.path("accessToken").asText()).isNotBlank();
        assertThat(jdbc.queryForObject(
            "select count(*) from user_identities where subject = ?", Integer.class, subject)).isEqualTo(1);
    }

    // ---------- 수용 기준 2: 위조/타 앱 토큰 401, 제공자 장애 503 ----------

    @Test
    @DisplayName("위조·다른 앱 토큰은 401 INVALID_SOCIAL_TOKEN, 제공자 타임아웃은 503")
    void invalidSocialTokenIs401AndProviderOutageIs503() throws Exception {
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify("forged")).thenThrow(new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN));
        when(kakao.verify("slow")).thenThrow(new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"forged\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SOCIAL_TOKEN"));
        mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"slow\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_UNAVAILABLE"));
    }

    // ---------- 수용 기준 3: 동시 가입 ----------

    @Test
    @DisplayName("동일 (provider, subject) 동시 가입 2건 → 사용자 1명, 둘 다 성공(1건만 newUser=true)")
    void concurrentSignupCreatesSingleUser() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        String token = "kakao-token-" + UUID.randomUUID();
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify(token)).thenReturn(new SocialProfile(subject, null, "동시가입"));

        // 두 요청이 모두 "아직 가입 안 됨"으로 조회한 뒤 동시에 가입을 시도하도록 맞춘다.
        // 그래야 늦게 커밋한 쪽이 유니크 제약 위반 → 재조회 복구 경로를 반드시 지난다.
        UserIdentityRepository realIdentities = new JpaRepositoryFactory(em).getRepository(UserIdentityRepository.class);
        CyclicBarrier afterLookup = new CyclicBarrier(2);
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(inv -> {
            if (lookups.incrementAndGet() <= 2) {
                afterLookup.await(5, TimeUnit.SECONDS);
                return Optional.empty();
            }
            // 인터페이스 spy는 실제 메서드를 못 부르므로, 같은 EntityManager로 만든 실제 레포지토리에 위임한다.
            return realIdentities.findByProviderAndSubject(inv.getArgument(0), inv.getArgument(1));
        }).when(identityRepository).findByProviderAndSubject(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = List.of(
                pool.submit(() -> loginResult(token)),
                pool.submit(() -> loginResult(token)));
            int newUsers = 0;
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).isEqualTo(200);
                if (data(r).path("newUser").asBoolean()) {
                    newUsers++;
                }
            }
            assertThat(newUsers).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from user_identities where subject = ?", Integer.class, subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from users u join user_identities i on i.user_id = u.id where i.subject = ?",
            Integer.class, subject)).isEqualTo(1);
    }

    private MvcResult loginResult(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}")).andReturn();
    }

    // ---------- 수용 기준 4: Rotation / 재사용 탐지 ----------

    @Test
    @DisplayName("Refresh 갱신 시 새 토큰이 발급되고, 10초가 지난 뒤 사용된 토큰을 재사용하면 family 전체가 폐기되고 401")
    void refreshRotationAndReuseDetectionRevokesFamily() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        JsonNode loginData = login("kakao", stubKakao(subject), "iPhone");
        String r1 = loginData.path("refreshToken").asText();

        MvcResult rotated = refresh(r1);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String r2 = data(rotated).path("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        // r1이 사용된 지 10초 초과로 만든다(유예 시간 경과 시뮬레이션).
        jdbc.update("update refresh_tokens set used_at = now() - interval '60 seconds' "
            + "where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", r1);

        assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
        // family 전체 폐기: 정상 흐름의 최신 토큰 r2도 더는 쓸 수 없다.
        assertThat(refresh(r2).getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- 수용 기준 5: 해시만 저장 ----------

    @Test
    @DisplayName("DB에는 Refresh 원문이 없고 SHA-256 해시만 저장된다")
    void refreshTokenStoredAsHashOnly() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        String raw = login("kakao", stubKakao(subject), "iPhone").path("refreshToken").asText();

        List<String> hashes = jdbc.queryForList(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userIdOf("KAKAO", subject));
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).isNotEqualTo(raw).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject(
            "select count(*) from refresh_tokens where token_hash = ?", Integer.class, raw)).isZero();
    }

    // ---------- 수용 기준 6: 세션 격리 ----------

    @Test
    @DisplayName("기기 A 로그아웃은 기기 B에 영향이 없고, 다른 사용자의 세션은 조회·삭제할 수 없다")
    void sessionsAreIsolatedPerDeviceAndPerUser() throws Exception {
        String subjectA = "k-" + UUID.randomUUID();
        JsonNode deviceA = login("kakao", stubKakao(subjectA), "기기A");
        JsonNode deviceB = login("kakao", stubKakao(subjectA), "기기B");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + deviceA.path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + deviceA.path("refreshToken").asText() + "\"}"))
            .andExpect(status().isOk());

        assertThat(refresh(deviceA.path("refreshToken").asText()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(deviceB.path("refreshToken").asText()).getResponse().getStatus()).isEqualTo(200);

        // 다른 사용자 C
        JsonNode other = login("kakao", stubKakao("k-" + UUID.randomUUID()), "기기C");
        String otherBearer = "Bearer " + other.path("accessToken").asText();

        MvcResult othersList = mockMvc.perform(get("/api/v1/auth/sessions").header("Authorization", otherBearer))
            .andExpect(status().isOk()).andReturn();
        assertThat(data(othersList)).hasSize(1);

        MvcResult ownerList = mockMvc.perform(get("/api/v1/auth/sessions")
                .header("Authorization", "Bearer " + deviceB.path("accessToken").asText()))
            .andExpect(status().isOk()).andReturn();
        String sessionIdOfOwner = data(ownerList).get(0).path("id").asText();

        mockMvc.perform(delete("/api/v1/auth/sessions/" + sessionIdOfOwner).header("Authorization", otherBearer))
            .andExpect(status().isNotFound());
        // 소유자의 세션은 삭제되지 않고 그대로 남아 있다
        MvcResult after = mockMvc.perform(get("/api/v1/auth/sessions")
                .header("Authorization", "Bearer " + deviceB.path("accessToken").asText()))
            .andExpect(status().isOk()).andReturn();
        assertThat(data(after)).hasSize(1);
        assertThat(data(after).get(0).path("id").asText()).isEqualTo(sessionIdOfOwner);
    }

    // ---------- 수용 기준 7: JWT 필터와 @GroupMemberOnly ----------

    @Test
    @DisplayName("JWT 없으면 401, 만료 JWT는 401 TOKEN_EXPIRED, 유효 JWT면 @GroupMemberOnly 멤버십 검사가 동작한다")
    void jwtFilterAndGroupMemberOnly() throws Exception {
        UUID groupId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/test-groups/" + groupId)).andExpect(status().isUnauthorized());

        String subject = "k-" + UUID.randomUUID();
        JsonNode me = login("kakao", stubKakao(subject), "iPhone");
        String bearer = "Bearer " + me.path("accessToken").asText();

        // 멤버가 아니면 403 GROUP_ACCESS_DENIED
        mockMvc.perform(get("/api/v1/test-groups/" + groupId).header("Authorization", bearer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("GROUP_ACCESS_DENIED"));

        // 멤버로 등록하면 통과
        UUID memberGroupId = tx.execute(s -> {
            User user = em.find(User.class, userIdOf("KAKAO", subject));
            FamilyGroup group = FamilyGroup.create("테스트 가족", user);
            em.persist(group);
            em.persist(GroupMember.create(group, user, MemberRole.PARENT, "엄마", null, Instant.now()));
            return group.getId();
        });
        mockMvc.perform(get("/api/v1/test-groups/" + memberGroupId).header("Authorization", bearer))
            .andExpect(status().isOk());

        // 만료된 JWT
        String expired = Jwts.builder()
            .subject(userIdOf("KAKAO", subject).toString())
            .claim("tier", MembershipTier.FREE.name())
            .issuedAt(new Date(System.currentTimeMillis() - 3_600_000))
            .expiration(new Date(System.currentTimeMillis() - 1_800_000))
            .signWith(Keys.hmacShaKeyFor(jwtProperties.jwtSecret().getBytes(StandardCharsets.UTF_8)))
            .compact();
        mockMvc.perform(get("/api/v1/test-groups/" + memberGroupId).header("Authorization", "Bearer " + expired))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        // 위조(서명 불일치) JWT는 일반 401
        mockMvc.perform(get("/api/v1/test-groups/" + memberGroupId).header("Authorization", "Bearer abc.def.ghi"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---------- 수용 기준 8: 동시 Refresh (ADR 결정 7) ----------

    @Test
    @DisplayName("같은 Refresh로 동시 2건 → 정확히 한 건만 사용 처리되고, 유예(10초) 내 재사용은 추가 발급된다")
    void concurrentRefreshMarksUsedExactlyOnce() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        String r1 = login("kakao", stubKakao(subject), "iPhone").path("refreshToken").asText();
        UUID userId = userIdOf("KAKAO", subject);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Integer>> results = List.of(
                pool.submit(() -> {
                    barrier.await();
                    return refresh(r1).getResponse().getStatus();
                }),
                pool.submit(() -> {
                    barrier.await();
                    return refresh(r1).getResponse().getStatus();
                }));
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(200); // 유예 내 재사용은 로그아웃시키지 않는다
            }
        } finally {
            pool.shutdownNow();
        }
        // 원본 1 + 발급 2, 그중 사용 처리된 것은 원본 1건뿐
        assertThat(jdbc.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "select count(*) from refresh_tokens where user_id = ? and used_at is not null",
            Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(distinct family_id) from refresh_tokens where user_id = ?",
            Integer.class, userId)).isEqualTo(1);
    }

    // ---------- 수용 기준 9: 탈퇴·복구 (ADR 결정 8) ----------

    @Test
    @DisplayName("탈퇴 후 모든 Refresh가 무효, 유예 내 로그인은 403 ACCOUNT_PENDING_DELETION(토큰 없음), restore 성공")
    void withdrawalBlocksLoginUntilRestored() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        JsonNode device1 = login("kakao", stubKakao(subject), "기기1");
        JsonNode device2 = login("kakao", stubKakao(subject), "기기2");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + device1.path("accessToken").asText()))
            .andExpect(status().isOk());

        assertThat(refresh(device1.path("refreshToken").asText()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(device2.path("refreshToken").asText()).getResponse().getStatus()).isEqualTo(401);

        String again = stubKakao(subject);
        mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + again + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_DELETION"))
            .andExpect(jsonPath("$.data.restorableUntil").isNotEmpty())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        MvcResult restored = mockMvc.perform(post("/api/v1/auth/kakao/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + again + "\"}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(data(restored).path("accessToken").asText()).isNotBlank();
        assertThat(jdbc.queryForObject(
            "select deleted_at from users where id = ?", Object.class, userIdOf("KAKAO", subject))).isNull();
        // 복구 후 일반 로그인 가능
        assertThat(login("kakao", again, "기기1").path("newUser").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("복구 유예(30일)가 지나면 로그인·restore 모두 403 ACCOUNT_RESTORE_EXPIRED")
    void expiredGracePeriodRejectsLoginAndRestore() throws Exception {
        String subject = "k-" + UUID.randomUUID();
        login("kakao", stubKakao(subject), "iPhone");
        jdbc.update("update users set deleted_at = now() - interval '31 days' where id = ?",
            userIdOf("KAKAO", subject));

        String token = stubKakao(subject);
        mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_RESTORE_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/kakao/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_RESTORE_EXPIRED"));
    }

    // ---------- 검수 반영 ----------

    private String expiredAccessToken() {
        return Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("tier", MembershipTier.FREE.name())
            .issuedAt(new Date(System.currentTimeMillis() - 3_600_000))
            .expiration(new Date(System.currentTimeMillis() - 1_800_000))
            .signWith(Keys.hmacShaKeyFor(jwtProperties.jwtSecret().getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    @Test
    @DisplayName("만료된 Access 토큰이 헤더에 붙어 있어도 공개 로그인·갱신 엔드포인트는 막히지 않는다")
    void expiredAccessTokenDoesNotBlockPublicAuthEndpoints() throws Exception {
        String token = stubKakao("k-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/auth/kakao").header("Authorization", "Bearer " + expiredAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("공개 경로(health·policies·api-docs)는 만료 Bearer가 붙어도 200, 보호 경로는 TOKEN_EXPIRED 유지")
    void publicPathsIgnoreExpiredBearerButProtectedPathsKeepTokenExpired() throws Exception {
        String bearer = "Bearer " + expiredAccessToken();

        mockMvc.perform(get("/api/v1/health").header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/policies").header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer)).andExpect(status().isOk());

        // 모바일 자동 갱신 흐름이 의존하므로 보호 경로는 반드시 TOKEN_EXPIRED여야 한다.
        mockMvc.perform(get("/api/v1/auth/sessions").header("Authorization", bearer))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("Apple 첫 로그인에서 앱이 보낸 이름이 저장된다")
    void appleFirstLoginStoresClientProvidedName() throws Exception {
        String subject = "a-" + UUID.randomUUID();
        when(apple.provider()).thenReturn(OAuthProvider.APPLE);
        when(apple.verify("apple-token")).thenReturn(new SocialProfile(subject, "me@privaterelay.test", null));

        mockMvc.perform(post("/api/v1/auth/apple").contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"token\":\"apple-token\",\"name\":\"김애플\"}"))
            .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class,
            userIdOf("APPLE", subject))).isEqualTo("김애플");
        assertThat(jdbc.queryForObject("select email from user_identities where subject = ?", String.class,
            subject)).isEqualTo("me@privaterelay.test");
    }

    @Test
    @DisplayName("Rotation 후에도 세션 목록은 기기당 1건이고 최초 로그인 시각이 유지되며 lastUsedAt이 채워진다")
    void sessionListIsOnePerDeviceAfterRotation() throws Exception {
        JsonNode login = login("kakao", stubKakao("k-" + UUID.randomUUID()), "내 폰");
        MvcResult rotated = refresh(login.path("refreshToken").asText());
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        // 유예 내 재사용으로 같은 family에 미사용 토큰이 하나 더 생겨도 1건으로 보여야 한다.
        assertThat(refresh(login.path("refreshToken").asText()).getResponse().getStatus()).isEqualTo(200);

        MvcResult list = mockMvc.perform(get("/api/v1/auth/sessions")
                .header("Authorization", "Bearer " + login.path("accessToken").asText()))
            .andExpect(status().isOk()).andReturn();

        assertThat(data(list)).hasSize(1);
        JsonNode session = data(list).get(0);
        assertThat(session.path("deviceLabel").asText()).isEqualTo("내 폰");
        assertThat(session.path("lastUsedAt").isNull()).isFalse();
        assertThat(Instant.parse(session.path("createdAt").asText()))
            .isBefore(Instant.parse(session.path("lastUsedAt").asText()));
    }
}
