package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.group.notify.GroupNotifier;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class GroupApiIntegrationTest {

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
    @MockBean
    private GroupNotifier notifier;

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

    private UUID createGroup(UUID owner, String name, String nickname) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/groups")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new java.util.HashMap<String, String>() {{
                    put("name", name);
                    put("nickname", nickname);
                }})))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(body(r).path("data").path("id").asText());
    }

    private String issueCode(UUID owner, UUID groupId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/groups/" + groupId + "/invite-code")
                .header("Authorization", bearer(owner)))
            .andExpect(status().isOk())
            .andReturn();
        return body(r).path("data").path("code").asText();
    }

    private MvcResult join(UUID user, String code, String nickname) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/join")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"code\":\"" + code + "\",\"nickname\":\"" + nickname + "\"}"))
            .andReturn();
    }

    private String memberStatus(UUID groupId, UUID userId) {
        return jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?",
            String.class, groupId, userId);
    }

    // ---------- 수용 기준: 생성자는 방장, 코드는 6자리·그룹당 활성 1개 ----------

    @Test
    @DisplayName("그룹 생성자는 ACTIVE 방장이고 내 그룹 목록에 나타난다")
    void creatorIsActiveOwner() throws Exception {
        UUID owner = newUser("엄마");
        UUID groupId = createGroup(owner, "우리 가족", "엄마");

        assertThat(memberStatus(groupId, owner)).isEqualTo("ACTIVE");
        Boolean isOwner = jdbc.queryForObject(
            "select is_owner from group_members where group_id = ? and user_id = ?",
            Boolean.class, groupId, owner);
        assertThat(isOwner).isTrue();

        mockMvc.perform(get("/api/v1/groups").header("Authorization", bearer(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(groupId.toString()))
            .andExpect(jsonPath("$.data[0].owner").value(true));
    }

    @Test
    @DisplayName("코드는 6자리 숫자이고 재발급하면 이전 코드는 폐기되어 활성 코드는 그룹당 1개다")
    void inviteCodeIsSixDigitsAndOneActivePerGroup() throws Exception {
        UUID owner = newUser("아빠");
        UUID groupId = createGroup(owner, "가족", "아빠");

        String first = null;
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String code = issueCode(owner, groupId);
            assertThat(code).matches("^[0-9]{6}$");
            if (first == null) {
                first = code;
            }
            issued.add(code);
        }

        Integer active = jdbc.queryForObject(
            "select count(*) from group_invites where group_id = ? and is_active", Integer.class, groupId);
        assertThat(active).isEqualTo(1);
        mockMvc.perform(get("/api/v1/groups/" + groupId + "/invite-code").header("Authorization", bearer(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").isNotEmpty());
    }

    @Test
    @DisplayName("앞자리가 0인 코드도 6자리로 보존된다")
    void leadingZeroCodeIsPreserved() throws Exception {
        UUID owner = newUser("앞자리");
        UUID groupId = createGroup(owner, "가족", "나");
        UUID joiner = newUser("입장자");
        issueCode(owner, groupId);
        jdbc.update("update group_invites set invite_code = '000123' where group_id = ? and is_active", groupId);

        MvcResult r = join(joiner, "000123", "나");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(memberStatus(groupId, joiner)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("서로 다른 그룹의 활성 코드는 중복될 수 없다(DB 유니크)")
    void activeCodesAreUniqueAcrossGroups() throws Exception {
        UUID owner1 = newUser("방장1");
        UUID owner2 = newUser("방장2");
        UUID g1 = createGroup(owner1, "가족1", "나");
        UUID g2 = createGroup(owner2, "가족2", "나");
        issueCode(owner1, g1);
        issueCode(owner2, g2);
        String code1 = jdbc.queryForObject(
            "select invite_code from group_invites where group_id = ? and is_active", String.class, g1);

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DuplicateKeyException.class,
            () -> jdbc.update("update group_invites set invite_code = ? where group_id = ? and is_active", code1, g2));
    }

    @Test
    @DisplayName("방장이 아닌 멤버는 코드를 발급할 수 없고(403), 다른 그룹 방장도 거부된다")
    void onlyOwnerCanIssueCode() throws Exception {
        UUID owner = newUser("방장");
        UUID member = newUser("멤버");
        UUID otherOwner = newUser("다른방장");
        UUID groupId = createGroup(owner, "가족", "방장");
        createGroup(otherOwner, "남의 가족", "다른방장");
        String code = issueCode(owner, groupId);
        join(member, code, "멤버");
        jdbc.update("update group_members set status = 'ACTIVE' where group_id = ? and user_id = ?", groupId, member);

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invite-code").header("Authorization", bearer(member)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_GROUP_OWNER"));
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invite-code").header("Authorization", bearer(otherOwner)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("GROUP_ACCESS_DENIED"));
    }

    // ---------- 수용 기준: 입장 요청 ----------

    @Test
    @DisplayName("없는 코드는 404, 만료된 코드는 410이며 둘 다 멤버십을 만들지 않는다")
    void unknownAndExpiredCodesAreRejected() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);

        String unknown = "000000".equals(code) ? "999999" : "000000";
        MvcResult notFound = join(joiner, unknown, "나");
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(notFound).path("code").asText()).isEqualTo("INVITE_CODE_NOT_FOUND");

        jdbc.update("update group_invites set expires_at = now() - interval '1 second' where group_id = ?", groupId);
        MvcResult expired = join(joiner, code, "나");
        assertThat(expired.getResponse().getStatus()).isEqualTo(410);
        assertThat(body(expired).path("code").asText()).isEqualTo("INVITE_CODE_EXPIRED");

        Integer rows = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and user_id = ?", Integer.class, groupId, joiner);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("재발급으로 폐기된 이전 코드는 입장할 수 없다")
    void replacedCodeIsRejected() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String oldCode = issueCode(owner, groupId);
        String newCode = issueCode(owner, groupId);
        org.junit.jupiter.api.Assumptions.assumeTrue(!oldCode.equals(newCode));

        assertThat(join(joiner, oldCode, "나").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("유효 코드는 PENDING이 되고 응답에 그룹 정보가 없으며, 입장 전에는 그룹 상세를 볼 수 없다")
    void validCodeCreatesPendingWithoutGroupInfo() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner, "비밀 가족 이름", "방장");
        String code = issueCode(owner, groupId);

        MvcResult r = join(joiner, code, "막내");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(r).path("data");
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.fieldNames().next()).isEqualTo("status");
        assertThat(data.size()).isEqualTo(1);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .doesNotContain("비밀 가족 이름").doesNotContain(groupId.toString());
        assertThat(memberStatus(groupId, joiner)).isEqualTo("PENDING");

        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", bearer(joiner)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups").header("Authorization", bearer(joiner)))
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("같은 사용자의 동시 2건 입장 요청 → 행 1개, 둘 다 성공 응답")
    void concurrentJoinsOfSameUserCreateOneRow() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Integer>> results = List.of(
                pool.submit(() -> {
                    barrier.await();
                    return join(joiner, code, "나").getResponse().getStatus();
                }),
                pool.submit(() -> {
                    barrier.await();
                    return join(joiner, code, "나").getResponse().getStatus();
                }));
            for (Future<Integer> f : results) {
                assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer rows = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and user_id = ?", Integer.class, groupId, joiner);
        assertThat(rows).isEqualTo(1);
        assertThat(memberStatus(groupId, joiner)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("이미 ACTIVE 멤버가 코드를 입력해도 성공(멱등)하고 상태는 그대로 ACTIVE다")
    void joinIsIdempotentForActiveMember() throws Exception {
        UUID owner = newUser("방장");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);

        MvcResult r = join(owner, code, "방장");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).path("data").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(memberStatus(groupId, owner)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("REMOVED는 코드로 재입장 불가(403), LEFT는 같은 행을 이어 PENDING으로 재요청된다")
    void removedCannotRejoinButLeftCan() throws Exception {
        UUID owner = newUser("방장");
        UUID removed = newUser("강퇴자");
        UUID left = newUser("이탈자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);
        join(removed, code, "강퇴자");
        join(left, code, "이탈자");
        jdbc.update("update group_members set status = 'REMOVED' where group_id = ? and user_id = ?", groupId, removed);
        jdbc.update("update group_members set status = 'LEFT', left_at = now() where group_id = ? and user_id = ?", groupId, left);

        MvcResult removedResult = join(removed, code, "강퇴자");
        assertThat(removedResult.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(removedResult).path("code").asText()).isEqualTo("JOIN_NOT_ALLOWED");
        assertThat(memberStatus(groupId, removed)).isEqualTo("REMOVED");

        UUID rowBefore = jdbc.queryForObject(
            "select id from group_members where group_id = ? and user_id = ?", UUID.class, groupId, left);
        MvcResult leftResult = join(left, code, "새이름");
        assertThat(leftResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(memberStatus(groupId, left)).isEqualTo("PENDING");
        UUID rowAfter = jdbc.queryForObject(
            "select id from group_members where group_id = ? and user_id = ?", UUID.class, groupId, left);
        assertThat(rowAfter).isEqualTo(rowBefore);
    }

    @Test
    @DisplayName("2일이 지난 PENDING은 만료로 취급되어 다시 입장 요청하면 요청 시각이 갱신된다")
    void expiredPendingIsRenewedOnRequest() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);
        join(joiner, code, "나");
        jdbc.update("update group_members set requested_at = now() - interval '3 days' where group_id = ? and user_id = ?",
            groupId, joiner);

        assertThat(join(joiner, code, "나").getResponse().getStatus()).isEqualTo(200);
        Instant requestedAt = jdbc.queryForObject(
            "select requested_at from group_members where group_id = ? and user_id = ?",
            java.sql.Timestamp.class, groupId, joiner).toInstant();
        assertThat(requestedAt).isAfter(Instant.now().minusSeconds(60));
        assertThat(memberStatus(groupId, joiner)).isEqualTo("PENDING");
    }

    // ---------- 수용 기준: 호칭 ----------

    @Test
    @DisplayName("호칭은 보는 사람마다 독립적이고, 없으면 입장 때 정한 이름이 보인다")
    void aliasesAreIndependentPerViewer() throws Exception {
        UUID owner = newUser("방장");
        UUID viewerA = newUser("자식A");
        UUID viewerB = newUser("자식B");
        UUID groupId = createGroup(owner, "가족", "엄마");
        String code = issueCode(owner, groupId);
        for (UUID u : List.of(viewerA, viewerB)) {
            join(u, code, "자식");
            jdbc.update("update group_members set status = 'ACTIVE' where group_id = ? and user_id = ?", groupId, u);
        }

        mockMvc.perform(put("/api/v1/groups/" + groupId + "/aliases/" + owner)
                .header("Authorization", bearer(viewerA))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"alias\":\"우리 엄마\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/groups/" + groupId + "/aliases/" + owner)
                .header("Authorization", bearer(viewerB))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"alias\":\"어머니\"}"))
            .andExpect(status().isOk());

        assertThat(displayNameOf(viewerA, groupId, owner)).isEqualTo("우리 엄마");
        assertThat(displayNameOf(viewerB, groupId, owner)).isEqualTo("어머니");
        // 호칭을 정하지 않은 방장 본인은 입장 때 정한 이름을 본다.
        assertThat(displayNameOf(owner, groupId, owner)).isEqualTo("엄마");

        // 다른 사람의 호칭 목록에 내 호칭이 섞이지 않는다.
        mockMvc.perform(get("/api/v1/groups/" + groupId + "/aliases").header("Authorization", bearer(viewerA)))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].alias").value("우리 엄마"));
        mockMvc.perform(get("/api/v1/groups/" + groupId + "/aliases").header("Authorization", bearer(owner)))
            .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/aliases/" + owner).header("Authorization", bearer(viewerA)))
            .andExpect(status().isOk());
        assertThat(displayNameOf(viewerA, groupId, owner)).isEqualTo("엄마");
        assertThat(displayNameOf(viewerB, groupId, owner)).isEqualTo("어머니");
    }

    @Test
    @DisplayName("다른 그룹의 멤버는 호칭을 조회·수정할 수 없고, 그룹 밖 사용자를 대상으로 지정할 수 없다")
    void aliasAccessIsScopedToGroup() throws Exception {
        UUID owner = newUser("방장");
        UUID outsider = newUser("외부인");
        UUID groupId = createGroup(owner, "가족", "방장");
        createGroup(outsider, "남의 가족", "외부인");

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/aliases").header("Authorization", bearer(outsider)))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/groups/" + groupId + "/aliases/" + owner)
                .header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"침입\"}"))
            .andExpect(status().isForbidden());
        // 그룹에 속한 적 없는 사용자를 대상으로 지정 → 404
        mockMvc.perform(put("/api/v1/groups/" + groupId + "/aliases/" + outsider)
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"누구\"}"))
            .andExpect(status().isNotFound());
        Integer rows = jdbc.queryForObject("select count(*) from member_aliases where group_id = ?", Integer.class, groupId);
        assertThat(rows).isZero();
    }

    private String displayNameOf(UUID viewer, UUID groupId, UUID target) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", bearer(viewer)))
            .andExpect(status().isOk())
            .andReturn();
        for (JsonNode m : body(r).path("data").path("members")) {
            if (m.path("userId").asText().equals(target.toString())) {
                return m.path("displayName").asText();
            }
        }
        throw new AssertionError("멤버를 찾을 수 없음");
    }

    // ---------- 수용 기준: 관리자 문의 ----------

    @Test
    @DisplayName("멤버의 문의는 DB에 저장되고, 멤버가 아니면 거부된다")
    void supportRequestIsStored() throws Exception {
        UUID owner = newUser("방장");
        UUID outsider = newUser("외부인");
        UUID groupId = createGroup(owner, "가족", "방장");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/support-requests")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"message\":\"20명이 넘는 대가족이에요\"}"))
            .andExpect(status().isCreated());
        String saved = jdbc.queryForObject(
            "select message from support_requests where group_id = ? and user_id = ?", String.class, groupId, owner);
        assertThat(saved).isEqualTo("20명이 넘는 대가족이에요");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/support-requests")
                .header("Authorization", bearer(outsider))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"침입\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("새 입장 요청은 방장에게 알림 포트를 한 번 호출하고, 멱등 재요청은 다시 알리지 않는다")
    void ownerIsNotifiedOnceOnNewRequest() throws Exception {
        UUID owner = newUser("알림방장");
        UUID joiner = newUser("알림입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);

        join(joiner, code, "나");
        join(joiner, code, "나");

        verify(notifier, times(1)).joinRequested(eq(groupId), eq(owner), eq(joiner));
    }

    @Test
    @DisplayName("LEFT 사용자의 동시 재요청 2건 → 행 1개, 둘 다 성공, 알림은 한 번만 간다")
    void concurrentRejoinOfLeftUserNotifiesOnce() throws Exception {
        UUID owner = newUser("방장");
        UUID joiner = newUser("재입장자");
        UUID groupId = createGroup(owner, "가족", "방장");
        String code = issueCode(owner, groupId);
        join(joiner, code, "나");
        jdbc.update("update group_members set status = 'LEFT', left_at = now() where group_id = ? and user_id = ?",
            groupId, joiner);
        clearInvocations(notifier);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Integer>> results = List.of(
                pool.submit(() -> {
                    barrier.await();
                    return join(joiner, code, "나").getResponse().getStatus();
                }),
                pool.submit(() -> {
                    barrier.await();
                    return join(joiner, code, "나").getResponse().getStatus();
                }));
            for (Future<Integer> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        Integer rows = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and user_id = ?", Integer.class, groupId, joiner);
        assertThat(rows).isEqualTo(1);
        assertThat(memberStatus(groupId, joiner)).isEqualTo("PENDING");
        verify(notifier, times(1)).joinRequested(any(), any(), any());
    }

    @Test
    @DisplayName("입장 실패(없는 코드)에는 알림이 가지 않는다")
    void failedJoinDoesNotNotify() throws Exception {
        UUID joiner = newUser("실패자");

        join(joiner, "000000", "나");

        verify(notifier, never()).joinRequested(any(), any(), eq(joiner));
    }

    @Test
    @DisplayName("폐기된 코드의 번호는 다른 그룹의 활성 코드로 다시 쓸 수 있다")
    void inactiveCodeNumberCanBeReused() throws Exception {
        UUID owner1 = newUser("방장1");
        UUID owner2 = newUser("방장2");
        UUID g1 = createGroup(owner1, "가족1", "나");
        UUID g2 = createGroup(owner2, "가족2", "나");
        issueCode(owner1, g1);
        String oldCode = jdbc.queryForObject(
            "select invite_code from group_invites where group_id = ? and is_active", String.class, g1);
        issueCode(owner1, g1); // 이전 코드 폐기
        issueCode(owner2, g2);

        jdbc.update("update group_invites set invite_code = ? where group_id = ? and is_active", oldCode, g2);

        String reused = jdbc.queryForObject(
            "select invite_code from group_invites where group_id = ? and is_active", String.class, g2);
        assertThat(reused).isEqualTo(oldCode);
    }

    @Test
    @DisplayName("한 사용자는 여러 그룹에 속할 수 있다(N:M)")
    void userCanBelongToMultipleGroups() throws Exception {
        UUID owner = newUser("방장");
        UUID multi = newUser("다중");
        UUID g1 = createGroup(owner, "가족1", "방장");
        UUID g2 = createGroup(multi, "가족2", "다중");
        join(multi, issueCode(owner, g1), "다중");
        jdbc.update("update group_members set status = 'ACTIVE' where group_id = ? and user_id = ?", g1, multi);

        mockMvc.perform(get("/api/v1/groups").header("Authorization", bearer(multi)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        assertThat(g1).isNotEqualTo(g2);
    }
}
