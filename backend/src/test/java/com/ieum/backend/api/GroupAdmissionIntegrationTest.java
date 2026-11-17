package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

/** 승인·상한 동시성·강퇴·방장 위임·입장 시도 제한(ADR-0003 결정 3·4·9·10). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class GroupAdmissionIntegrationTest {

    private static final String UNKNOWN_CODE = "000000";

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
    @PersistenceContext
    private EntityManager em;

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

    private UUID createGroup(UUID owner) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/groups")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"name\":\"가족\",\"nickname\":\"방장\"}"))
            .andReturn();
        return UUID.fromString(body(r).path("data").path("id").asText());
    }

    private String issueCode(UUID owner, UUID groupId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/groups/" + groupId + "/invite-code")
                .header("Authorization", bearer(owner)))
            .andReturn();
        return body(r).path("data").path("code").asText();
    }

    private MvcResult join(UUID user, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/join")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"nickname\":\"입장\"}"))
            .andReturn();
    }

    private int postStatus(UUID actor, String path) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", bearer(actor)))
            .andReturn().getResponse().getStatus();
    }

    private MvcResult postFull(UUID actor, String path) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn();
    }

    private int approve(UUID owner, UUID groupId, UUID target) throws Exception {
        return postStatus(owner, "/api/v1/groups/" + groupId + "/join-requests/" + target + "/approve");
    }

    private UUID addMember(UUID groupId, String status, String joinedAt) {
        UUID user = newUser("m" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
            "insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at, requested_at) "
                + "values (?, ?, 'CHILD', '멤버', ?, " + joinedAt + ", now())",
            groupId, user, status);
        return user;
    }

    private String statusOf(UUID groupId, UUID userId) {
        return jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?", String.class, groupId, userId);
    }

    private int activeCount(UUID groupId) {
        return jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and status = 'ACTIVE'", Integer.class, groupId);
    }

    // ---------- 승인 ----------

    @Test
    @DisplayName("방장 승인 시 ACTIVE가 되고, 방장이 아니면 403, 다른 그룹 방장의 승인도 403이다")
    void ownerApprovesOthersAreDenied() throws Exception {
        UUID owner = newUser("방장");
        UUID otherOwner = newUser("다른방장");
        UUID member = newUser("멤버");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner);
        createGroup(otherOwner);
        String code = issueCode(owner, groupId);
        join(joiner, code);
        UUID activeMember = addMember(groupId, "ACTIVE", "now()");

        assertThat(approve(activeMember, groupId, joiner)).isEqualTo(403); // 일반 멤버
        assertThat(approve(otherOwner, groupId, joiner)).isEqualTo(403);   // 다른 그룹 방장
        assertThat(approve(member, groupId, joiner)).isEqualTo(403);       // 비멤버
        assertThat(statusOf(groupId, joiner)).isEqualTo("PENDING");

        assertThat(approve(owner, groupId, joiner)).isEqualTo(200);
        assertThat(statusOf(groupId, joiner)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("방장은 만료되지 않은 승인 대기 목록만 본다")
    void pendingListExcludesExpired() throws Exception {
        UUID owner = newUser("방장");
        UUID fresh = newUser("신규");
        UUID stale = newUser("만료");
        UUID groupId = createGroup(owner);
        String code = issueCode(owner, groupId);
        join(fresh, code);
        join(stale, code);
        jdbc.update("update group_members set requested_at = now() - interval '3 days' where group_id = ? and user_id = ?",
            groupId, stale);

        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + groupId + "/join-requests")
                .header("Authorization", bearer(owner)))
            .andReturn();
        JsonNode list = body(r).path("data");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("userId").asText()).isEqualTo(fresh.toString());
    }

    @Test
    @DisplayName("2일이 지난 승인 대기는 만료되어 승인할 수 없다(410)")
    void expiredRequestCannotBeApproved() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner);
        join(joiner, issueCode(owner, groupId));
        jdbc.update("update group_members set requested_at = now() - interval '2 days 1 minute' where group_id = ? and user_id = ?",
            groupId, joiner);

        assertThat(approve(owner, groupId, joiner)).isEqualTo(410);
        assertThat(statusOf(groupId, joiner)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("거절된 사용자는 LEFT가 되고 다시 요청할 수 있으며, 강퇴(REMOVED)는 코드로 재입장할 수 없다")
    void rejectAllowsRetryButRemovalDoesNot() throws Exception {
        UUID owner = newUser("방장");
        UUID rejected = newUser("거절");
        UUID kicked = newUser("강퇴");
        UUID groupId = createGroup(owner);
        String code = issueCode(owner, groupId);
        join(rejected, code);
        join(kicked, code);
        approve(owner, groupId, kicked);

        assertThat(postStatus(owner, "/api/v1/groups/" + groupId + "/join-requests/" + rejected + "/reject")).isEqualTo(200);
        assertThat(statusOf(groupId, rejected)).isEqualTo("LEFT");
        assertThat(join(rejected, code).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(groupId, rejected)).isEqualTo("PENDING");

        assertThat(mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + kicked)
            .header("Authorization", bearer(owner))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(groupId, kicked)).isEqualTo("REMOVED");
        assertThat(join(kicked, code).getResponse().getStatus()).isEqualTo(403);
        assertThat(statusOf(groupId, kicked)).isEqualTo("REMOVED");
    }

    @Test
    @DisplayName("방장 전용 API(대기 목록·거절·강퇴·코드 조회)는 일반 멤버와 다른 그룹 방장에게 403이고, 방장은 본인을 강퇴할 수 없다")
    void ownerOnlyEndpointsRejectOthers() throws Exception {
        UUID owner = newUser("방장");
        UUID otherOwner = newUser("다른방장");
        UUID groupId = createGroup(owner);
        createGroup(otherOwner);
        UUID member = addMember(groupId, "ACTIVE", "now()");
        UUID pending = addMember(groupId, "PENDING", "now()");
        issueCode(owner, groupId);

        for (UUID intruder : List.of(member, otherOwner)) {
            assertThat(mockMvc.perform(get("/api/v1/groups/" + groupId + "/join-requests")
                .header("Authorization", bearer(intruder))).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mockMvc.perform(get("/api/v1/groups/" + groupId + "/invite-code")
                .header("Authorization", bearer(intruder))).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(postStatus(intruder, "/api/v1/groups/" + groupId + "/join-requests/" + pending + "/reject"))
                .isEqualTo(403);
            assertThat(mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + member)
                .header("Authorization", bearer(intruder))).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(statusOf(groupId, pending)).isEqualTo("PENDING");
        assertThat(statusOf(groupId, member)).isEqualTo("ACTIVE");

        assertThat(mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + owner)
            .header("Authorization", bearer(owner))).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(statusOf(groupId, owner)).isEqualTo("ACTIVE");
    }

    // ---------- 인원 상한 동시성 ----------

    @Test
    @DisplayName("19명 방에 동시 승인 2건 → 정확히 1건만 성공하고 20명을 넘지 않는다")
    void concurrentApprovalsNeverExceedLimit() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        for (int i = 0; i < 18; i++) {
            addMember(groupId, "ACTIVE", "now()");
        }
        assertThat(activeCount(groupId)).isEqualTo(19);
        UUID p1 = addMember(groupId, "PENDING", "now()");
        UUID p2 = addMember(groupId, "PENDING", "now()");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Integer> f1 = pool.submit(() -> {
                barrier.await();
                return approve(owner, groupId, p1);
            });
            Future<Integer> f2 = pool.submit(() -> {
                barrier.await();
                return approve(owner, groupId, p2);
            });
            List<Integer> results = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeCount(groupId)).isEqualTo(20);
        Integer pending = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and status = 'PENDING'", Integer.class, groupId);
        assertThat(pending).isEqualTo(1); // 상한에 걸린 요청은 PENDING으로 남는다.
    }

    @Test
    @DisplayName("상한 20명이 찬 방은 GROUP_FULL(409)이고, 코드는 GROUP_FULL 메시지를 돌려준다")
    void fullGroupRejectsApproval() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        for (int i = 0; i < 19; i++) {
            addMember(groupId, "ACTIVE", "now()");
        }
        UUID pending = addMember(groupId, "PENDING", "now()");

        MvcResult r = mockMvc.perform(post("/api/v1/groups/" + groupId + "/join-requests/" + pending + "/approve")
            .header("Authorization", bearer(owner))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).path("code").asText()).isEqualTo("GROUP_FULL");
        assertThat(activeCount(groupId)).isEqualTo(20);
    }

    @Test
    @DisplayName("승인·강퇴·이탈은 모두 같은 그룹 행 잠금을 기다린다(잠금을 쥔 동안 완료되지 않고, 풀리면 완료된다)")
    void approveKickAndLeaveSerializeOnTheGroupRowLock() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        UUID pending = addMember(groupId, "PENDING", "now()");
        UUID kickTarget = addMember(groupId, "ACTIVE", "now()");
        UUID leaver = addMember(groupId, "ACTIVE", "now()");

        List<Callable<Integer>> operations = List.of(
            () -> approve(owner, groupId, pending),
            () -> mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + kickTarget)
                .header("Authorization", bearer(owner))).andReturn().getResponse().getStatus(),
            () -> postFull(leaver, "/api/v1/groups/" + groupId + "/leave").getResponse().getStatus());

        for (Callable<Integer> operation : operations) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                    jdbc.queryForObject("select id from family_groups where id = ? for update", UUID.class, groupId);
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                Future<Integer> blocked = pool.submit(operation);
                assertThatThrownBy(() -> blocked.get(1500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertThat(blocked.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
        assertThat(statusOf(groupId, pending)).isEqualTo("ACTIVE");
        assertThat(statusOf(groupId, kickTarget)).isEqualTo("REMOVED");
        assertThat(statusOf(groupId, leaver)).isEqualTo("LEFT");
    }

    // ---------- 방장 이탈·위임 ----------

    @Test
    @DisplayName("방장 이탈 시 지정한 멤버에게 위임되고, 그룹당 ACTIVE 방장은 1명이다")
    void ownerLeavesToDesignatedSuccessor() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        addMember(groupId, "ACTIVE", "now() - interval '2 days'");
        UUID chosen = addMember(groupId, "ACTIVE", "now()");

        MvcResult r = mockMvc.perform(post("/api/v1/groups/" + groupId + "/leave")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"successorUserId\":\"" + chosen + "\"}"))
            .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(groupId, owner)).isEqualTo("LEFT");
        assertThat(ownersOf(groupId)).containsExactly(chosen);
    }

    @Test
    @DisplayName("지정이 없으면 가장 오래된 ACTIVE에게 위임하고, PENDING은 후보에서 제외된다")
    void defaultSuccessorIsOldestActiveIgnoringPending() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        addMember(groupId, "PENDING", "now() - interval '10 days'");
        UUID oldest = addMember(groupId, "ACTIVE", "now() - interval '3 days'");
        addMember(groupId, "ACTIVE", "now() - interval '1 day'");

        assertThat(postFull(owner, "/api/v1/groups/" + groupId + "/leave").getResponse().getStatus()).isEqualTo(200);
        assertThat(ownersOf(groupId)).containsExactly(oldest);
    }

    @Test
    @DisplayName("가입 일시가 같으면 id 순(DB 정렬)으로 위임한다")
    void tieIsBrokenById() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        String sameTime = "timestamptz '2026-01-01 00:00:00+00'";
        addMember(groupId, "ACTIVE", sameTime);
        addMember(groupId, "ACTIVE", sameTime);
        addMember(groupId, "ACTIVE", sameTime);
        UUID expected = jdbc.queryForObject(
            "select user_id from group_members where group_id = ? and status = 'ACTIVE' and not is_owner "
                + "order by joined_at, id limit 1", UUID.class, groupId);

        assertThat(postFull(owner, "/api/v1/groups/" + groupId + "/leave").getResponse().getStatus()).isEqualTo(200);
        assertThat(ownersOf(groupId)).containsExactly(expected);
    }

    @Test
    @DisplayName("혼자 남은 방장이 나가면 그룹이 해산되고 코드와 대기 요청이 정리된다")
    void lastOwnerLeavingDissolvesGroup() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        issueCode(owner, groupId);
        UUID pending = addMember(groupId, "PENDING", "now()");

        assertThat(postFull(owner, "/api/v1/groups/" + groupId + "/leave").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from family_groups where id = ?", String.class, groupId))
            .isEqualTo("ARCHIVED");
        assertThat(statusOf(groupId, owner)).isEqualTo("LEFT");
        assertThat(statusOf(groupId, pending)).isEqualTo("LEFT");
        assertThat(ownersOf(groupId)).isEmpty();
        Integer activeCodes = jdbc.queryForObject(
            "select count(*) from group_invites where group_id = ? and is_active", Integer.class, groupId);
        assertThat(activeCodes).isZero();
    }

    @Test
    @DisplayName("일반 멤버가 나가면 LEFT가 되고, 후계자가 ACTIVE가 아니면 거부(400)되어 방장이 유지된다")
    void invalidSuccessorIsRejected() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner);
        UUID active = addMember(groupId, "ACTIVE", "now()");
        UUID pending = addMember(groupId, "PENDING", "now()");

        MvcResult bad = mockMvc.perform(post("/api/v1/groups/" + groupId + "/leave")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"successorUserId\":\"" + pending + "\"}"))
            .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(ownersOf(groupId)).containsExactly(owner);
        assertThat(statusOf(groupId, owner)).isEqualTo("ACTIVE");

        assertThat(postFull(active, "/api/v1/groups/" + groupId + "/leave").getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(groupId, active)).isEqualTo("LEFT");
    }

    private List<UUID> ownersOf(UUID groupId) {
        return new ArrayList<>(jdbc.queryForList(
            "select user_id from group_members where group_id = ? and is_owner and status = 'ACTIVE'",
            UUID.class, groupId));
    }

    // ---------- 입장 시도 제한 ----------

    private void failTimes(UUID user, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(join(user, UNKNOWN_CODE).getResponse().getStatus()).isEqualTo(404);
        }
    }

    private int failCount(UUID user) {
        return jdbc.queryForObject("select fail_count from join_attempt_state where user_id = ?", Integer.class, user);
    }

    private void endLockAndWindow(UUID user) {
        jdbc.update("update join_attempt_state set locked_until = now() - interval '1 second', "
            + "window_started_at = now() - interval '1 hour' where user_id = ?", user);
    }

    @Test
    @DisplayName("5번 실패하면 20분 잠금(429, 남은 시간 포함)이고, 잠금 중에는 유효 코드도 거부된다")
    void fiveFailuresLockEvenValidCode() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner);
        String valid = issueCode(owner, groupId);

        failTimes(joiner, 5);
        assertThat(failCount(joiner)).isEqualTo(5); // 실패 기록은 예외 이후에도 남는다.

        MvcResult locked = join(joiner, valid);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(locked).path("code").asText()).isEqualTo("JOIN_LOCKED");
        long retryAfter = body(locked).path("data").path("retryAfterSeconds").asLong();
        assertThat(retryAfter).isBetween(1100L, 1200L);
        assertThat(locked.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(retryAfter));
        assertThat(body(locked).path("message").asText()).contains("분");
        Integer rows = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and user_id = ?", Integer.class, groupId, joiner);
        assertThat(rows).isZero();

        // 잠금이 풀리면(시각 비교) 유효 코드로 입장 요청이 가능하다.
        endLockAndWindow(joiner);
        assertThat(join(joiner, valid).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 사용자의 동시 10건 → 코드 검증은 5번을 넘지 않고 나머지는 429다")
    void concurrentAttemptsNeverValidateMoreThanFive() throws Exception {
        UUID joiner = newUser("무차별");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return join(joiner, UNKNOWN_CODE).getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(5);
            assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failCount(joiner)).isEqualTo(5);
    }

    @Test
    @DisplayName("잠금 3회 누적 → security_events에 기록되고, 2회까지는 기록되지 않는다")
    void threeLocksAreRecordedAsSecurityEvent() throws Exception {
        UUID joiner = newUser("이상사용자");

        for (int round = 1; round <= 3; round++) {
            failTimes(joiner, 5);
            Integer lockCount = jdbc.queryForObject(
                "select lock_count from join_attempt_state where user_id = ?", Integer.class, joiner);
            assertThat(lockCount).isEqualTo(round);
            Integer events = jdbc.queryForObject(
                "select count(*) from security_events where user_id = ?", Integer.class, joiner);
            assertThat(events).isEqualTo(round < 3 ? 0 : 1);
            endLockAndWindow(joiner);
        }
        String type = jdbc.queryForObject(
            "select event_type from security_events where user_id = ?", String.class, joiner);
        assertThat(type).isEqualTo("JOIN_ATTEMPT_ANOMALY");
        // 자동 차단은 없다: 잠금이 풀리면 다시 시도할 수 있다.
        assertThat(join(joiner, UNKNOWN_CODE).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("잠금 누적 기간(24시간)이 지나면 누적이 처음부터 다시 센다")
    void lockCountResetsAfterAnomalyWindow() throws Exception {
        UUID joiner = newUser("오래전");

        failTimes(joiner, 5);
        endLockAndWindow(joiner);
        jdbc.update("update join_attempt_state set lock_count_started_at = now() - interval '25 hours' where user_id = ?",
            joiner);
        failTimes(joiner, 5);

        Integer lockCount = jdbc.queryForObject(
            "select lock_count from join_attempt_state where user_id = ?", Integer.class, joiner);
        assertThat(lockCount).isEqualTo(1);
    }

    @Test
    @DisplayName("성공해도 실패 횟수는 리셋되지 않아 이후 실패가 이어서 쌓이고 잠금에 이른다")
    void successDoesNotResetFailCount() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner);
        String valid = issueCode(owner, groupId);

        failTimes(joiner, 3);
        assertThat(join(joiner, valid).getResponse().getStatus()).isEqualTo(200);
        assertThat(failCount(joiner)).isEqualTo(3);
        Integer lockCount = jdbc.queryForObject(
            "select lock_count from join_attempt_state where user_id = ?", Integer.class, joiner);
        assertThat(lockCount).isZero();

        failTimes(joiner, 2);
        assertThat(join(joiner, valid).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("5분 창이 지나면 다음 실패부터 횟수를 새로 센다(배치 없이 시각 비교)")
    void failureWindowExpiresLazily() throws Exception {
        UUID joiner = newUser("느린사용자");

        failTimes(joiner, 4);
        jdbc.update("update join_attempt_state set window_started_at = now() - interval '6 minutes' where user_id = ?",
            joiner);
        failTimes(joiner, 1);

        assertThat(failCount(joiner)).isEqualTo(1);
        assertThat(join(joiner, UNKNOWN_CODE).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("만료된 코드 입력도 실패로 기록된다")
    void expiredCodeCountsAsFailure() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner);
        String code = issueCode(owner, groupId);
        jdbc.update("update group_invites set expires_at = now() - interval '1 second' where group_id = ?", groupId);

        assertThat(join(joiner, code).getResponse().getStatus()).isEqualTo(410);
        assertThat(failCount(joiner)).isEqualTo(1);
    }
}
