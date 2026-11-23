package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.account.purge.AccountPurgeTransaction;
import com.ieum.backend.domain.auth.service.AccountService;
import com.ieum.backend.domain.common.enums.ContentChoice;
import com.ieum.backend.domain.common.enums.EmojiCode;
import com.ieum.backend.domain.group.service.GroupMembershipService;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.reaction.service.ReactionService;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.TimeZoneConstants;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 이모지 리액션(멱등 PUT/DELETE, 서버 권한)과 아카이브 타임라인(키셋 페이징, 서버 마스킹). ADR-0008. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ReactionArchiveIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 19);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.EPOCH;

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Instant instant() { return now; }
        @Override public Clock withZone(ZoneId z) { return this; }
        void set(Instant instant) { this.now = instant; }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ReactionService reactionService;
    @Autowired private AccountService accountService;
    @Autowired private AccountPurgeTransaction purgeTransaction;
    @Autowired private GroupMembershipService membershipService;
    @Autowired private MutableClock clock;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void resetState() {
        jdbc.update("delete from daily_questions");
        jdbc.update("update family_groups set status = 'ARCHIVED'");
        jdbc.update("delete from questions");
        for (int i = 1; i <= 8; i++) {
            jdbc.update("insert into questions (sequence_order, category, question_text) values (?, 'DAILY', ?)", i, "테스트 질문 " + i);
        }
        clock.set(ZonedDateTime.of(DAY1, LocalTime.of(10, 0), KST).toInstant());
    }

    // ---------- helpers ----------

    private UUID newUser(String name) {
        return tx.execute(s -> {
            User user = User.create(name + "-" + UUID.randomUUID() + "@test.dev", name, MemberRole.CHILD);
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

    private record Family(UUID groupId, List<UUID> members, UUID dqId) {
        UUID owner() { return members.get(0); }
    }

    /** 방장 1명 + 일반 멤버 size-1명, DAY1 질문 1개(LOCKED). */
    private Family newFamily(int size) throws Exception {
        List<UUID> members = new ArrayList<>();
        UUID owner = newUser("엄마");
        members.add(owner);
        MvcResult r = mockMvc.perform(post("/api/v1/groups")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"name\":\"우리 가족\",\"nickname\":\"엄마\"}"))
            .andExpect(status().isCreated()).andReturn();
        UUID groupId = UUID.fromString(body(r).path("data").path("id").asText());
        for (int i = 1; i < size; i++) {
            UUID u = newUser("멤버" + i);
            members.add(u);
            jdbc.update("insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at) "
                + "values (?, ?, 'CHILD', ?, 'ACTIVE', now() + (? * interval '1 second'))", groupId, u, "멤버" + i, i);
        }
        return new Family(groupId, members, addQuestion(groupId, DAY1, 1));
    }

    private UUID addQuestion(UUID groupId, LocalDate date, int sequence) {
        UUID dq = UUID.randomUUID();
        jdbc.update("insert into daily_questions (id, group_id, question_id, cycle_date, reveal_status, assigned_at, auto_reveal_at) "
                + "values (?, ?, (select id from questions where sequence_order = ?), ?, 'LOCKED', ?, ?)",
            dq, groupId, sequence, date,
            Timestamp.from(TimeZoneConstants.assignedAt(date)),
            Timestamp.from(TimeZoneConstants.autoRevealAt(date)));
        return dq;
    }

    private UUID insertAnswer(UUID groupId, UUID dqId, UUID userId, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into answers (id, daily_question_id, group_id, user_id, text_content, input_method) "
            + "values (?, ?, ?, ?, ?, 'TEXT_ONLY')", id, dqId, groupId, userId, text);
        return id;
    }

    private void setStatus(UUID dqId, String status) {
        jdbc.update("update daily_questions set reveal_status = ? where id = ?", status, dqId);
    }

    private int countReactions(UUID answerId, UUID userId, EmojiCode emoji) {
        return jdbc.queryForObject("select count(*) from answer_reactions where answer_id = ? and user_id = ? and emoji_code = ?",
            Integer.class, answerId, userId, emoji.name());
    }

    private MvcResult react(String method, UUID groupId, UUID answerId, UUID viewer, String emoji) throws Exception {
        String url = "/api/v1/groups/" + groupId + "/answers/" + answerId + "/reactions/" + emoji;
        var builder = "PUT".equals(method) ? put(url) : delete(url);
        return mockMvc.perform(builder.header("Authorization", bearer(viewer))).andReturn();
    }

    private int putStatus(UUID groupId, UUID answerId, UUID viewer, String emoji) throws Exception {
        return react("PUT", groupId, answerId, viewer, emoji).getResponse().getStatus();
    }

    private JsonNode archive(UUID groupId, UUID viewer, String query) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + groupId + "/archive" + query)
                .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andReturn();
        return body(r).path("data");
    }

    private String archiveRaw(UUID groupId, UUID viewer) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/" + groupId + "/archive")
                .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode reactionList(UUID groupId, UUID answerId, UUID viewer) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + groupId + "/answers/" + answerId + "/reactions")
                .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andReturn();
        return body(r).path("data");
    }

    private static JsonNode emoji(JsonNode reactions, String code) {
        for (JsonNode n : reactions) {
            if (code.equals(n.path("emojiCode").asText())) {
                return n;
            }
        }
        throw new AssertionError(code + " not found");
    }

    // ---------- 리액션: 멱등·동시성 ----------

    @RepeatedTest(3)
    @DisplayName("같은 사람이 같은 이모지를 동시에 PUT해도 정확히 1건이다")
    void concurrentSamePutCreatesOneRow() throws Exception {
        Family f = newFamily(3);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID answer = insertAnswer(f.groupId(), f.dqId(), f.members().get(1), "답변 본문");
        UUID me = f.members().get(2);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return reactionService.add(f.groupId(), answer, me, EmojiCode.LOVE);
            }));
        }
        start.countDown();
        for (Future<?> fu : futures) {
            fu.get(20, TimeUnit.SECONDS); // 예외(유일 위반 포함)가 나면 실패
        }
        pool.shutdownNow();

        assertThat(countReactions(answer, me, EmojiCode.LOVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT과 DELETE를 반복해도 오류 없이 멱등이다")
    void putAndDeleteAreIdempotent() throws Exception {
        Family f = newFamily(2);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID answer = insertAnswer(f.groupId(), f.dqId(), f.owner(), "답변 본문");
        UUID me = f.members().get(1);

        for (int i = 0; i < 3; i++) {
            assertThat(putStatus(f.groupId(), answer, me, "LOVE")).isEqualTo(200);
        }
        assertThat(countReactions(answer, me, EmojiCode.LOVE)).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            assertThat(react("DELETE", f.groupId(), answer, me, "LOVE").getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(countReactions(answer, me, EmojiCode.LOVE)).isZero();
    }

    @Test
    @DisplayName("한 사람이 한 답변에 서로 다른 종류를 여럿 달 수 있고 본인 답변에도 달 수 있다")
    void multipleKindsAndOwnAnswer() throws Exception {
        Family f = newFamily(2);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID mine = insertAnswer(f.groupId(), f.dqId(), f.owner(), "내 답변");
        for (String code : List.of("LOVE", "TOUCHED", "THANKS", "FUNNY", "HUG")) {
            assertThat(putStatus(f.groupId(), mine, f.owner(), code)).isEqualTo(200);
        }
        MvcResult r = react("DELETE", f.groupId(), mine, f.owner(), "FUNNY");
        JsonNode reactions = body(r).path("data").path("reactions");
        assertThat(reactions).hasSize(5);
        assertThat(emoji(reactions, "LOVE").path("count").asInt()).isEqualTo(1);
        assertThat(emoji(reactions, "LOVE").path("mine").asBoolean()).isTrue();
        assertThat(emoji(reactions, "FUNNY").path("count").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from answer_reactions where answer_id = ?", Integer.class, mine))
            .isEqualTo(4);
    }

    @Test
    @DisplayName("DELETE도 PUT과 같은 열람 권한을 확인해, 공개 전·열람 불가 답변의 집계를 내려주지 않는다")
    void deleteChecksVisibilityToo() throws Exception {
        Family f = newFamily(3);
        UUID locked = insertAnswer(f.groupId(), f.dqId(), f.owner(), "잠긴 답변");
        MvcResult r = react("DELETE", f.groupId(), locked, f.members().get(1), "LOVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).path("code").asText()).isEqualTo("ANSWER_NOT_VISIBLE");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("reactions");
    }

    @Test
    @DisplayName("알 수 없는 이모지는 400이다")
    void unknownEmojiRejected() throws Exception {
        Family f = newFamily(2);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID answer = insertAnswer(f.groupId(), f.dqId(), f.owner(), "본문");
        assertThat(putStatus(f.groupId(), answer, f.members().get(1), "ANGRY")).isEqualTo(400);
    }

    @RepeatedTest(5)
    @DisplayName("리액션이 방장 탈퇴·멤버 강퇴와 동시에 일어나도 교착 없이 끝난다(락 순서 ADR-0007 결정 9)")
    void reactionDoesNotDeadlockWithWithdrawalAndRemoval() throws Exception {
        Family f = newFamily(4);
        setStatus(f.dqId(), "REVEALED_ALL");
        addQuestion(f.groupId(), DAY1.plusDays(1), 2); // LOCKED 질문: 탈퇴·강퇴가 재평가로 이 행을 잠근다
        UUID target = insertAnswer(f.groupId(), f.dqId(), f.members().get(3), "대상 답변");
        UUID reactor = f.members().get(1);
        UUID removed = f.members().get(2);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 15; i++) {
                try {
                    reactionService.add(f.groupId(), target, reactor, EmojiCode.values()[i % 5]);
                    reactionService.remove(f.groupId(), target, reactor, EmojiCode.values()[i % 5]);
                } catch (com.ieum.backend.global.exception.BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(com.ieum.backend.global.exception.ErrorCode.GROUP_ACCESS_DENIED);
                }
            }
            return null;
        }));
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 15; i++) {
                try {
                    reactionService.add(f.groupId(), target, removed, EmojiCode.HUG);
                } catch (com.ieum.backend.global.exception.BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(com.ieum.backend.global.exception.ErrorCode.GROUP_ACCESS_DENIED);
                }
            }
            return null;
        }));
        futures.add(pool.submit(() -> {
            start.await();
            membershipService.remove(f.groupId(), f.owner(), removed);
            return null;
        }));
        futures.add(pool.submit(() -> {
            start.await();
            accountService.withdraw(reactor, ContentChoice.KEEP);
            return null;
        }));
        start.countDown();
        for (Future<?> fu : futures) {
            fu.get(30, TimeUnit.SECONDS); // 교착이면 lock_timeout(15초) 또는 여기서 실패한다
        }
        pool.shutdownNow();
    }

    @RepeatedTest(4)
    @DisplayName("작성자 파기(답변 삭제)와 리액션 PUT이 겹쳐도 교착 없이 끝나고, 남는 리액션은 고아가 아니다")
    void reactionDoesNotDeadlockWithAuthorPurge() throws Exception {
        Family f = newFamily(4);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID author = f.members().get(3);
        UUID target = insertAnswer(f.groupId(), f.dqId(), author, "파기될 작성자의 답변");
        jdbc.update("update users set deleted_at = now() - interval '31 days', purge_started_at = now() where id = ?", author);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (UUID reactor : List.of(f.members().get(1), f.members().get(2))) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 20; i++) {
                    try {
                        reactionService.add(f.groupId(), target, reactor, EmojiCode.values()[i % 5]);
                    } catch (com.ieum.backend.global.exception.BusinessException e) {
                        assertThat(e.getErrorCode()).isEqualTo(com.ieum.backend.global.exception.ErrorCode.RESOURCE_NOT_FOUND);
                    }
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            return purgeTransaction.finalizeUser(author, true);
        }));
        start.countDown();
        for (Future<?> fu : futures) {
            fu.get(30, TimeUnit.SECONDS); // 교착이면 lock_timeout(15초) 또는 여기서 실패한다
        }
        pool.shutdownNow();

        assertThat(jdbc.queryForObject("select count(*) from answers where id = ?", Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from answer_reactions where answer_id = ?", Integer.class, target))
            .as("답변이 지워지면 달린 리액션도 함께 사라진다").isZero();
    }

    // ---------- 리액션: 서버 권한 ----------

    @Test
    @DisplayName("LOCKED 답변에는 본인 답변이어도 PUT이 거절된다")
    void lockedRejected() throws Exception {
        Family f = newFamily(2);
        UUID mine = insertAnswer(f.groupId(), f.dqId(), f.owner(), "내 답변");
        UUID other = insertAnswer(f.groupId(), f.dqId(), f.members().get(1), "타인 답변");

        MvcResult r = react("PUT", f.groupId(), mine, f.owner(), "LOVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).path("code").asText()).isEqualTo("ANSWER_NOT_VISIBLE");
        assertThat(putStatus(f.groupId(), other, f.owner(), "LOVE")).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from answer_reactions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("TIMEOUT 공개에서는 내가 답변을 마친 경우에만 리액션할 수 있다(지연 전환 포함)")
    void timeoutRequiresOwnAnswer() throws Exception {
        Family f = newFamily(3);
        UUID answererA = f.members().get(0);
        UUID answererB = f.members().get(1);
        UUID lateComer = f.members().get(2);
        UUID aAnswer = insertAnswer(f.groupId(), f.dqId(), answererA, "A 답변");
        insertAnswer(f.groupId(), f.dqId(), answererB, "B 답변");

        // 17시가 지났지만 DB는 아직 LOCKED: 조회 시점의 지연 전환이 일어나야 한다.
        clock.set(ZonedDateTime.of(DAY1.plusDays(1), LocalTime.of(18, 0), KST).toInstant());
        assertThat(putStatus(f.groupId(), aAnswer, answererB, "LOVE")).isEqualTo(200);
        assertThat(jdbc.queryForObject("select reveal_status from daily_questions where id = ?", String.class, f.dqId()))
            .isEqualTo("REVEALED_TIMEOUT");

        assertThat(putStatus(f.groupId(), aAnswer, lateComer, "LOVE")).isEqualTo(403);
        insertAnswer(f.groupId(), f.dqId(), lateComer, "늦은 답변");
        assertThat(putStatus(f.groupId(), aAnswer, lateComer, "LOVE")).isEqualTo(200);
    }

    @Test
    @DisplayName("비멤버·탈퇴 유예 사용자·다른 그룹의 답변은 거절된다")
    void nonMemberGraceUserAndForeignAnswerRejected() throws Exception {
        Family f = newFamily(3);
        Family other = newFamily(2);
        setStatus(f.dqId(), "REVEALED_ALL");
        setStatus(other.dqId(), "REVEALED_ALL");
        UUID answer = insertAnswer(f.groupId(), f.dqId(), f.owner(), "본문");
        UUID foreign = insertAnswer(other.groupId(), other.dqId(), other.owner(), "남의 그룹");

        assertThat(putStatus(f.groupId(), answer, other.owner(), "LOVE")).isEqualTo(403); // 비멤버
        assertThat(putStatus(f.groupId(), foreign, f.owner(), "LOVE")).isEqualTo(404); // 답변이 이 그룹 것이 아님

        UUID grace = f.members().get(2);
        jdbc.update("update users set deleted_at = now() where id = ?", grace);
        assertThat(putStatus(f.groupId(), answer, grace, "LOVE")).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from answer_reactions", Integer.class)).isZero();
    }

    // ---------- 리액션 목록: 표기 규칙·마스킹 ----------

    @Test
    @DisplayName("탈퇴한 사용자의 리액션은 '탈퇴한 사용자'로, 보는 사람이 정한 호칭이 있으면 그 호칭으로 나온다")
    void reactorNames() throws Exception {
        Family f = newFamily(4);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID viewer = f.members().get(0);
        UUID leaver = f.members().get(1);
        UUID aliased = f.members().get(2);
        UUID author = f.members().get(3);
        UUID answer = insertAnswer(f.groupId(), f.dqId(), author, "본문");
        assertThat(putStatus(f.groupId(), answer, viewer, "LOVE")).isEqualTo(200);
        assertThat(putStatus(f.groupId(), answer, leaver, "LOVE")).isEqualTo(200);
        assertThat(putStatus(f.groupId(), answer, aliased, "HUG")).isEqualTo(200);

        jdbc.update("update users set deleted_at = now() where id = ?", leaver); // 탈퇴 유예: 리액션은 남는다
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, '큰애')",
            f.groupId(), viewer, aliased);

        JsonNode reactions = reactionList(f.groupId(), answer, viewer).path("reactions");
        List<String> loveNames = new ArrayList<>();
        emoji(reactions, "LOVE").path("reactors").forEach(n -> loveNames.add(n.path("displayName").asText()));
        assertThat(loveNames).containsExactlyInAnyOrder("엄마", "탈퇴한 사용자");
        assertThat(emoji(reactions, "LOVE").path("count").asInt()).isEqualTo(2);
        assertThat(emoji(reactions, "LOVE").path("mine").asBoolean()).isTrue();
        assertThat(emoji(reactions, "HUG").path("reactors").get(0).path("displayName").asText()).isEqualTo("큰애");
    }

    @Test
    @DisplayName("리액션 목록은 열람 권한이 없으면 거절된다")
    void reactionListMasked() throws Exception {
        Family f = newFamily(3);
        UUID a = insertAnswer(f.groupId(), f.dqId(), f.members().get(0), "A");
        insertAnswer(f.groupId(), f.dqId(), f.members().get(1), "B");
        clock.set(ZonedDateTime.of(DAY1.plusDays(1), LocalTime.of(18, 0), KST).toInstant()); // TIMEOUT 전환 대상, 3번은 미답변
        assertThat(mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/answers/" + a + "/reactions")
                .header("Authorization", bearer(f.members().get(2)))).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- 타임라인 ----------

    @Test
    @DisplayName("타임라인은 cycle_date 내림차순 커서로 이어 받고, 중간에 새 항목이 생겨도 중복·누락이 없다")
    void keysetPaginationStableUnderInsert() throws Exception {
        Family f = newFamily(2);
        jdbc.update("delete from daily_questions where id = ?", f.dqId());
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(addQuestion(f.groupId(), DAY1.minusDays(i + 1), i + 1)); // 최신 → 과거 순
        }
        UUID viewer = f.owner();

        JsonNode first = archive(f.groupId(), viewer, "?size=2");
        assertThat(first.path("items")).hasSize(2);
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        // 첫 페이지를 받은 뒤 더 최신 항목이 생긴다: OFFSET 방식이면 다음 페이지가 한 칸 밀려 중복이 난다.
        UUID newest = addQuestion(f.groupId(), DAY1, 6);

        List<String> seen = new ArrayList<>();
        first.path("items").forEach(n -> seen.add(n.path("dailyQuestionId").asText()));
        while (!cursor.isBlank()) {
            JsonNode page = archive(f.groupId(), viewer, "?size=2&cursor=" + cursor);
            page.path("items").forEach(n -> seen.add(n.path("dailyQuestionId").asText()));
            cursor = page.path("nextCursor").isNull() ? "" : page.path("nextCursor").asText();
        }

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(created.stream().map(UUID::toString).toList());
        assertThat(seen).doesNotContain(newest.toString());
        // 새 항목은 첫 페이지(커서 없음)에서 맨 앞에 보인다.
        assertThat(archive(f.groupId(), viewer, "?size=2").path("items").get(0).path("dailyQuestionId").asText())
            .isEqualTo(newest.toString());
    }

    @Test
    @DisplayName("잘못된 커서·페이지 크기는 400이다")
    void invalidCursorAndSize() throws Exception {
        Family f = newFamily(2);
        String trailing = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("2026-11-19|" + UUID.randomUUID() + "|").getBytes(StandardCharsets.UTF_8));
        for (String q : List.of("?cursor=not-a-cursor", "?cursor=" + trailing, "?size=0", "?size=1000")) {
            assertThat(mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/archive" + q)
                    .header("Authorization", bearer(f.owner()))).andReturn().getResponse().getStatus())
                .as(q).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("LOCKED 항목에는 타인 본문이 없고, 내 답변과 답했는지만 내려간다")
    void lockedItemHidesOthers() throws Exception {
        Family f = newFamily(3);
        insertAnswer(f.groupId(), f.dqId(), f.members().get(1), "아무도 보면 안 되는 비밀 본문");
        UUID mine = insertAnswer(f.groupId(), f.dqId(), f.members().get(0), "내 본문입니다");

        String raw = archiveRaw(f.groupId(), f.members().get(0));
        assertThat(raw).doesNotContain("비밀 본문").contains("내 본문입니다");
        JsonNode item = archive(f.groupId(), f.members().get(0), "").path("items").get(0);
        assertThat(item.path("revealStatus").asText()).isEqualTo("LOCKED");
        assertThat(item.path("hasAnswered").asBoolean()).isTrue();
        assertThat(item.path("answers")).hasSize(1);
        assertThat(item.path("answers").get(0).path("answerId").asText()).isEqualTo(mine.toString());

        // 답하지 않은 사람은 본문도, 답변 항목도 보지 못한다.
        String lateRaw = archiveRaw(f.groupId(), f.members().get(2));
        assertThat(lateRaw).doesNotContain("비밀 본문").doesNotContain("내 본문입니다");
    }

    @Test
    @DisplayName("TIMEOUT 항목은 답변을 마친 사람에게만 타인 본문과 리액션 집계가 내려간다")
    void timeoutItemMasking() throws Exception {
        Family f = newFamily(3);
        UUID a = insertAnswer(f.groupId(), f.dqId(), f.members().get(0), "A 본문입니다");
        insertAnswer(f.groupId(), f.dqId(), f.members().get(1), "B 본문입니다");
        clock.set(ZonedDateTime.of(DAY1.plusDays(1), LocalTime.of(18, 0), KST).toInstant());
        assertThat(putStatus(f.groupId(), a, f.members().get(1), "THANKS")).isEqualTo(200);

        JsonNode asB = archive(f.groupId(), f.members().get(1), "").path("items").get(0);
        assertThat(asB.path("answers")).hasSize(2);
        JsonNode aView = null;
        for (JsonNode n : asB.path("answers")) {
            if (n.path("answerId").asText().equals(a.toString())) {
                aView = n;
            }
        }
        assertThat(aView).isNotNull();
        assertThat(emoji(aView.path("reactions"), "THANKS").path("count").asInt()).isEqualTo(1);
        assertThat(emoji(aView.path("reactions"), "THANKS").path("mine").asBoolean()).isTrue();

        String asLate = archiveRaw(f.groupId(), f.members().get(2));
        assertThat(asLate).doesNotContain("A 본문").doesNotContain("B 본문");
    }

    @Test
    @DisplayName("타임라인의 작성자 표기: 탈퇴한 사용자는 '탈퇴한 사용자', 정한 호칭이 있으면 그 호칭")
    void archiveAuthorNames() throws Exception {
        Family f = newFamily(3);
        setStatus(f.dqId(), "REVEALED_ALL");
        UUID viewer = f.members().get(0);
        UUID leaver = f.members().get(1);
        UUID aliased = f.members().get(2);
        insertAnswer(f.groupId(), f.dqId(), leaver, "탈퇴 예정자의 답변");
        insertAnswer(f.groupId(), f.dqId(), aliased, "호칭 대상의 답변");
        jdbc.update("update users set deleted_at = now() where id = ?", leaver);
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, '둘째')",
            f.groupId(), viewer, aliased);

        Set<String> names = new HashSet<>();
        archive(f.groupId(), viewer, "").path("items").get(0).path("answers")
            .forEach(n -> names.add(n.path("authorName").asText()));
        assertThat(names).containsExactlyInAnyOrder("탈퇴한 사용자", "둘째");
    }
}
