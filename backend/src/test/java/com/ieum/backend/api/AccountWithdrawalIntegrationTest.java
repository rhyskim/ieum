package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.auth.service.AccountService;
import com.ieum.backend.domain.auth.social.AppleTokenVerifier;
import com.ieum.backend.domain.auth.social.KakaoTokenVerifier;
import com.ieum.backend.domain.auth.social.NaverTokenVerifier;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 탈퇴 요청 개편·유효 멤버 규칙·복구(ADR-0002 결정 6·8·11·12)의 수용 기준 테스트. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AccountWithdrawalIntegrationTest {

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
    private JwtProvider jwtProvider;
    @Autowired
    private AccountService accountService;
    @PersistenceContext
    private EntityManager em;

    @MockBean
    private KakaoTokenVerifier kakao;
    @MockBean
    private NaverTokenVerifier naver;
    @MockBean
    private AppleTokenVerifier apple;

    // ---------- helpers ----------

    private UUID newUser(String name) {
        return tx.execute(s -> {
            User user = User.create(name + "@test.dev", name, MemberRole.CHILD);
            em.persist(user);
            return user.getId();
        });
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId, MembershipTier.FREE);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 그룹을 만들고 방장 멤버십을 직접 넣는다(joined_at은 가장 오래된 시각). */
    private UUID newGroup(UUID owner) {
        UUID groupId = jdbc.queryForObject(
            "insert into family_groups (name, creator_id) values ('가족', ?) returning id", UUID.class, owner);
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, status, is_owner, joined_at) "
                + "values (?, ?, 'PARENT', '방장', 'ACTIVE', true, now() - interval '100 days')", groupId, owner);
        return groupId;
    }

    /** 멤버를 직접 넣는다. joinedDaysAgo가 클수록 오래된 멤버다. */
    private UUID addMember(UUID groupId, String status, int joinedDaysAgo) {
        UUID user = newUser("m" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at, requested_at) "
                + "values (?, ?, 'CHILD', '멤버', ?, now() - make_interval(days => ?), now())",
            groupId, user, status, joinedDaysAgo);
        return user;
    }

    private void addExistingMember(UUID groupId, UUID user, String status) {
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at, requested_at) "
                + "values (?, ?, 'CHILD', '멤버', ?, now(), now())", groupId, user, status);
    }

    private void fillTo(UUID groupId, int activeCount) {
        while (validActive(groupId) < activeCount) {
            addMember(groupId, "ACTIVE", 10);
        }
    }

    private int validActive(UUID groupId) {
        return jdbc.queryForObject(
            "select count(*) from group_members m join users u on u.id = m.user_id "
                + "where m.group_id = ? and m.status = 'ACTIVE' and u.deleted_at is null", Integer.class, groupId);
    }

    private String memberStatus(UUID groupId, UUID userId) {
        return jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, userId);
    }

    private boolean isOwner(UUID groupId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select is_owner from group_members where group_id = ? and user_id = ?", Boolean.class, groupId, userId));
    }

    private int withdraw(UUID userId, String json) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andReturn().getResponse().getStatus();
    }

    private int withdrawKeep(UUID userId) throws Exception {
        return withdraw(userId, "{\"contentChoice\":\"KEEP\"}");
    }

    /** 사용자에게 카카오 로그인 수단을 붙이고 복구 요청용 소셜 토큰을 돌려준다. */
    private String kakaoTokenFor(UUID userId) {
        String subject = "k-" + UUID.randomUUID();
        jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'KAKAO', ?)", userId, subject);
        String token = "kakao-" + UUID.randomUUID();
        when(kakao.provider()).thenReturn(OAuthProvider.KAKAO);
        when(kakao.verify(token)).thenReturn(new SocialProfile(subject, null, "카카오유저"));
        return token;
    }

    private MvcResult restore(String socialToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/kakao/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + socialToken + "\"}"))
            .andReturn();
    }

    private JsonNode groupDetail(UUID viewer, UUID groupId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", bearer(viewer)))
            .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r).path("data");
    }

    private boolean deleted(UUID userId) {
        return jdbc.queryForObject("select deleted_at is not null from users where id = ?", Boolean.class, userId);
    }

    // ---------- 수용 기준 1: 탈퇴 요청 ----------

    @Test
    @DisplayName("탈퇴 요청: 모든 Refresh 폐기, 기기 토큰 삭제, 콘텐츠 선택 저장")
    void withdrawalRevokesSessionsDropsDevicesAndStoresChoice() throws Exception {
        UUID user = newUser("탈퇴자");
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into refresh_tokens (user_id, family_id, token_hash, expires_at) "
                + "values (?, gen_random_uuid(), ?, now() + interval '60 days')", user, "hash-" + UUID.randomUUID());
            jdbc.update("insert into user_devices (user_id, fcm_token, device_os) values (?, ?, 'ANDROID')",
                user, "fcm-" + UUID.randomUUID());
        }

        assertThat(withdraw(user, "{\"contentChoice\":\"DELETE\"}")).isEqualTo(200);

        assertThat(jdbc.queryForObject(
            "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from user_devices where user_id = ?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject(
            "select withdraw_content_choice from users where id = ?", String.class, user)).isEqualTo("DELETE");
        assertThat(deleted(user)).isTrue();
    }

    @Test
    @DisplayName("콘텐츠 선택이 없거나 잘못되면 400이고 탈퇴되지 않는다")
    void withdrawalRequiresContentChoice() throws Exception {
        UUID user = newUser("선택누락");

        assertThat(withdraw(user, "{}")).isEqualTo(400);
        assertThat(withdraw(user, "{\"contentChoice\":\"MAYBE\"}")).isEqualTo(400);
        assertThat(deleted(user)).isFalse();
    }

    @Test
    @DisplayName("이미 탈퇴한 계정에 다시 요청해도 성공하고 처음 선택과 시각이 바뀌지 않는다(멱등)")
    void secondWithdrawalIsIdempotent() throws Exception {
        UUID user = newUser("두번탈퇴");
        assertThat(withdraw(user, "{\"contentChoice\":\"KEEP\"}")).isEqualTo(200);
        Object firstDeletedAt = jdbc.queryForObject("select deleted_at from users where id = ?", Object.class, user);

        assertThat(withdraw(user, "{\"contentChoice\":\"DELETE\"}")).isEqualTo(200);

        assertThat(jdbc.queryForObject("select withdraw_content_choice from users where id = ?", String.class, user))
            .isEqualTo("KEEP");
        assertThat(jdbc.queryForObject("select deleted_at from users where id = ?", Object.class, user))
            .isEqualTo(firstDeletedAt);
    }

    // ---------- 수용 기준 1: 방장 위임 ----------

    @Test
    @DisplayName("방장이 탈퇴하면 가장 오래된 유효 멤버(유예 중 제외)에게 즉시 위임되고, 복구해도 방장은 돌아오지 않는다")
    void ownerWithdrawalDelegatesToOldestValidMemberAndRestoreDoesNotReclaim() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID oldestButWithdrawn = addMember(groupId, "ACTIVE", 50);
        UUID expectedSuccessor = addMember(groupId, "ACTIVE", 40);
        addMember(groupId, "ACTIVE", 30);
        assertThat(withdrawKeep(oldestButWithdrawn)).isEqualTo(200);
        String ownerToken = kakaoTokenFor(owner);

        assertThat(withdrawKeep(owner)).isEqualTo(200);

        assertThat(isOwner(groupId, expectedSuccessor)).isTrue();
        assertThat(isOwner(groupId, oldestButWithdrawn)).isFalse(); // 유예 중인 사람은 방장 후보가 아니다
        assertThat(isOwner(groupId, owner)).isFalse();
        assertThat(memberStatus(groupId, owner)).isEqualTo("ACTIVE"); // 멤버 상태는 ACTIVE 유지(방식 a)

        assertThat(restore(ownerToken).getResponse().getStatus()).isEqualTo(200);

        assertThat(isOwner(groupId, owner)).isFalse();
        assertThat(isOwner(groupId, expectedSuccessor)).isTrue();
        assertThat(memberStatus(groupId, owner)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            Integer.class, groupId)).isEqualTo(1);
    }

    @Test
    @DisplayName("혼자 남은 방장이 탈퇴해도 요청 시점에는 그룹이 해산되지 않고 그대로 유지된다")
    void lonelyOwnerWithdrawalKeepsGroup() throws Exception {
        UUID owner = newUser("혼자방장");
        UUID groupId = newGroup(owner);

        assertThat(withdrawKeep(owner)).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ACTIVE");
        assertThat(isOwner(groupId, owner)).isTrue();
        assertThat(memberStatus(groupId, owner)).isEqualTo("ACTIVE");
    }

    // ---------- 수용 기준 2: 유효 멤버 ----------

    @Test
    @DisplayName("유예 중 사용자는 멤버 목록·승인 대기 목록에 보이지 않고, 접근 검사도 통과하지 못한다")
    void withdrawnUserIsHiddenFromMemberAndPendingListsAndAccess() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID withdrawnMember = addMember(groupId, "ACTIVE", 5);
        UUID visibleMember = addMember(groupId, "ACTIVE", 4);
        UUID withdrawnPending = addMember(groupId, "PENDING", 0);
        UUID visiblePending = addMember(groupId, "PENDING", 0);
        assertThat(withdrawKeep(withdrawnMember)).isEqualTo(200);
        assertThat(withdrawKeep(withdrawnPending)).isEqualTo(200);

        JsonNode members = groupDetail(owner, groupId).path("members");
        List<String> ids = new ArrayList<>();
        members.forEach(m -> ids.add(m.path("userId").asText()));
        assertThat(ids).contains(owner.toString(), visibleMember.toString());
        assertThat(ids).doesNotContain(withdrawnMember.toString());

        MvcResult pending = mockMvc.perform(get("/api/v1/groups/" + groupId + "/join-requests")
                .header("Authorization", bearer(owner)))
            .andReturn();
        List<String> pendingIds = new ArrayList<>();
        body(pending).path("data").forEach(p -> pendingIds.add(p.path("userId").asText()));
        assertThat(pendingIds).containsExactly(visiblePending.toString());

        // 탈퇴 직후에도 30분간 유효한 Access 토큰으로는 그룹에 접근할 수 없다.
        MvcResult denied = mockMvc.perform(get("/api/v1/groups/" + groupId)
                .header("Authorization", bearer(withdrawnMember)))
            .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("인원 상한 COUNT에서 유예 중 사용자는 제외된다: 20명 중 1명이 탈퇴하면 새 승인이 가능하고, 다시 20명이면 GROUP_FULL")
    void withdrawnUserIsExcludedFromMemberLimitCount() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        fillTo(groupId, 20);
        UUID leaver = addMemberCountedAmongTwenty(groupId);
        assertThat(withdrawKeep(leaver)).isEqualTo(200);
        assertThat(validActive(groupId)).isEqualTo(19);
        UUID joiner1 = addMember(groupId, "PENDING", 0);
        UUID joiner2 = addMember(groupId, "PENDING", 0);

        assertThat(approve(owner, groupId, joiner1)).isEqualTo(200);
        assertThat(validActive(groupId)).isEqualTo(20);
        assertThat(approve(owner, groupId, joiner2)).isEqualTo(409);
    }

    /** 이미 가득 찬(ACTIVE 20) 그룹의 한 멤버를 돌려준다. */
    private UUID addMemberCountedAmongTwenty(UUID groupId) {
        return jdbc.queryForObject(
            "select user_id from group_members where group_id = ? and is_owner = false and status = 'ACTIVE' limit 1",
            UUID.class, groupId);
    }

    private int approve(UUID owner, UUID groupId, UUID target) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/join-requests/" + target + "/approve")
                .header("Authorization", bearer(owner)))
            .andReturn().getResponse().getStatus();
    }

    // ---------- 수용 기준 3: 복구 ----------

    @Test
    @DisplayName("복구하면 원래 그룹에 ACTIVE로 돌아오고, 이미 20명인 그룹만 LEFT가 된다")
    void restoreReturnsToGroupsAndLeavesFullOnes() throws Exception {
        UUID user = newUser("복구자");
        UUID smallOwner = newUser("작은방장");
        UUID smallGroup = newGroup(smallOwner);
        addExistingMember(smallGroup, user, "ACTIVE");
        UUID fullOwner = newUser("큰방장");
        UUID fullGroup = newGroup(fullOwner);
        addExistingMember(fullGroup, user, "ACTIVE");
        fillTo(fullGroup, 20); // 방장 + 복구자 + 18명
        String token = kakaoTokenFor(user);

        assertThat(withdrawKeep(user)).isEqualTo(200);
        addMember(fullGroup, "ACTIVE", 1); // 유예 중에 빈 자리가 채워져 유효 20명이 된다
        assertThat(validActive(fullGroup)).isEqualTo(20);

        MvcResult r = restore(token);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(deleted(user)).isFalse();
        assertThat(memberStatus(smallGroup, user)).isEqualTo("ACTIVE");
        assertThat(memberStatus(fullGroup, user)).isEqualTo("LEFT");
        assertThat(validActive(fullGroup)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select withdraw_content_choice from users where id = ?", String.class, user))
            .isNull();
    }

    @Test
    @DisplayName("유예 중에 방장이 나가 그룹이 해산(ARCHIVED)되면, 복구해도 그 그룹으로는 돌아가지 않고 LEFT가 된다(Agy 검수 반영)")
    void restoreDoesNotReturnToGroupDissolvedDuringGrace() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        UUID member = addMember(groupId, "ACTIVE", 5);
        String token = kakaoTokenFor(member);
        assertThat(withdrawKeep(member)).isEqualTo(200);

        // 유효 멤버가 방장뿐이므로 방장이 나가면 그룹이 해산된다.
        int leave = mockMvc.perform(post("/api/v1/groups/" + groupId + "/leave")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus();
        assertThat(leave).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ARCHIVED");

        assertThat(restore(token).getResponse().getStatus()).isEqualTo(200);

        assertThat(deleted(member)).isFalse();
        assertThat(memberStatus(groupId, member)).isEqualTo("LEFT");
    }

    // ---------- 수용 기준 7: 복구와 파기 시작 경쟁 ----------

    @Test
    @DisplayName("복구 마감(30일)이 지난 계정은 복구되지 않고 deleted_at이 그대로다")
    void restoreAfterGraceIsRejected() throws Exception {
        UUID user = newUser("마감후");
        String token = kakaoTokenFor(user);
        assertThat(withdrawKeep(user)).isEqualTo(200);
        jdbc.update("update users set deleted_at = now() - interval '31 days' where id = ?", user);

        MvcResult r = restore(token);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).path("code").asText()).isEqualTo("ACCOUNT_RESTORE_EXPIRED");
        assertThat(deleted(user)).isTrue();
    }

    @Test
    @DisplayName("파기가 시작된 계정(purge_started_at)은 서버 시계로 아직 30일 이내여도 조건부 UPDATE가 막아 복구되지 않는다")
    void restoreAfterPurgeStartedIsRejectedEvenWithinGraceByClock() throws Exception {
        UUID user = newUser("시계차이");
        String token = kakaoTokenFor(user);
        assertThat(withdrawKeep(user)).isEqualTo(200);
        jdbc.update("update users set deleted_at = now() - interval '10 days', purge_started_at = now() where id = ?",
            user);

        MvcResult r = restore(token);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).path("code").asText()).isEqualTo("ACCOUNT_RESTORE_EXPIRED");
        assertThat(deleted(user)).isTrue();
    }

    @Test
    @DisplayName("같은 계정의 복구 2건이 AccountService.restore에 동시에 진입해도 둘 다 예외 없이 끝나고 복구 결과는 하나다")
    void concurrentRestoresBothSucceedAndRestoreOnce() throws Exception {
        UUID user = newUser("동시복구");
        UUID owner = newUser("방장");
        UUID groupId = newGroup(owner);
        addExistingMember(groupId, user, "ACTIVE");
        assertThat(withdrawKeep(user)).isEqualTo(200);

        // HTTP 경로에서는 한 요청이 먼저 커밋하면 뒤 요청이 서비스까지 오지 않을 수 있다.
        // 서비스에 직접 동시 진입시켜 조건부 UPDATE의 0행(이미 복구됨 → 멱등 성공) 분기를 반드시 지나게 한다.
        List<Integer> statuses = runConcurrently(
            () -> {
                accountService.restore(user);
                return 200;
            },
            () -> {
                accountService.restore(user);
                return 200;
            });

        assertThat(statuses).containsExactly(200, 200);
        assertThat(deleted(user)).isFalse();
        assertThat(memberStatus(groupId, user)).isEqualTo("ACTIVE");
    }

    // ---------- 수용 기준: 복구와 승인이 마지막 자리를 두고 경쟁 ----------

    @Test
    @DisplayName("마지막 한 자리를 두고 복구와 승인이 동시에 오면 유효 ACTIVE는 정확히 20명이고 한쪽만 자리를 얻는다")
    void restoreAndApproveRaceForLastSeat() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID owner = newUser("방장");
            UUID groupId = newGroup(owner);
            fillTo(groupId, 19);
            UUID user = newUser("복구자");
            addExistingMember(groupId, user, "ACTIVE"); // 유효 20명
            String token = kakaoTokenFor(user);
            assertThat(withdrawKeep(user)).isEqualTo(200); // 유효 19명, 자리 1개
            UUID joiner = addMember(groupId, "PENDING", 0);

            List<Integer> statuses = runConcurrently(
                () -> restore(token).getResponse().getStatus(),
                () -> approve(owner, groupId, joiner));

            assertThat(statuses.get(0)).isEqualTo(200); // 복구 요청 자체는 항상 성공(그룹이 LEFT여도 계정은 복구됨)
            assertThat(validActive(groupId)).isEqualTo(20);
            boolean restoreWon = "ACTIVE".equals(memberStatus(groupId, user));
            boolean approveWon = "ACTIVE".equals(memberStatus(groupId, joiner));
            assertThat(restoreWon ^ approveWon).as("정확히 한쪽만 마지막 자리를 얻는다").isTrue();
            assertThat(statuses.get(1)).isEqualTo(approveWon ? 200 : 409);
            if (!restoreWon) {
                assertThat(memberStatus(groupId, user)).isEqualTo("LEFT");
            }
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
