package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.answer.service.AnswerService;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.notification.push.PushResult;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.TimeZoneConstants;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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

/** 답변 저장과 공개 상태 머신(ADR-0007). 시각은 주입한 Clock으로 조작한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AnswerRevealIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 19);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.EPOCH;
        private final ZoneId zone;

        MutableClock(ZoneId zone) {
            this.zone = zone;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Instant instant() { return now; }
        @Override public Clock withZone(ZoneId z) {
            MutableClock parent = this;
            return new Clock() {
                @Override public ZoneId getZone() { return z; }
                @Override public Instant instant() { return parent.instant(); }
                @Override public Clock withZone(ZoneId other) { return parent.withZone(other); }
            };
        }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZoneId.of("UTC"));
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private AnswerService answerService;
    @Autowired private MutableClock clock;
    @PersistenceContext private EntityManager em;
    @MockBean private PushSender sender;
    @BeforeEach
    void resetState() throws Exception {
        reset(sender);
        when(sender.send(anyString(), any())).thenReturn(PushResult.SENT);
        jdbc.execute("drop trigger if exists test_answer_insert_delay on answers");
        jdbc.update("delete from daily_questions");
        jdbc.update("update family_groups set status = 'ARCHIVED'");
        jdbc.update("delete from questions");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("insert into questions (sequence_order, category, question_text) values (?, 'DAILY', ?)", i, "테스트 질문 " + i);
        }
    }

    // ---------- helpers ----------

    private void at(LocalDate date, int hour, int minute) {
        clock.set(ZonedDateTime.of(date, LocalTime.of(hour, minute), KST).toInstant());
    }

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

    /** 방장 1명 + 일반 멤버 members-1명, 각자 기기 토큰 1개, DAY1 질문 1개(LOCKED). */
    private record Family(UUID groupId, UUID dqId, List<UUID> members, List<String> tokens) {
        UUID owner() { return members.get(0); }
    }

    private Family newFamily(int size) throws Exception {
        List<UUID> members = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        UUID owner = newUser("엄마");
        members.add(owner);
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/groups")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"name\":\"우리 가족\",\"nickname\":\"엄마\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        UUID groupId = UUID.fromString(body(r).path("data").path("id").asText());
        for (int i = 1; i < size; i++) {
            UUID u = newUser("멤버" + i);
            members.add(u);
            jdbc.update("insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at) "
                + "values (?, ?, 'CHILD', ?, 'ACTIVE', now() + (? * interval '1 second'))", groupId, u, "멤버" + i, i);
        }
        for (UUID u : members) {
            String token = "tok-" + UUID.randomUUID();
            jdbc.update("insert into user_devices (user_id, fcm_token, device_os) values (?, ?, 'ANDROID')", u, token);
            tokens.add(token);
        }
        UUID dq = UUID.randomUUID();
        jdbc.update("insert into daily_questions (id, group_id, question_id, cycle_date, reveal_status, assigned_at, auto_reveal_at) "
                + "values (?, ?, (select id from questions where sequence_order = 1), ?, 'LOCKED', ?, ?)",
            dq, groupId, DAY1,
            java.sql.Timestamp.from(TimeZoneConstants.assignedAt(DAY1)),
            java.sql.Timestamp.from(TimeZoneConstants.autoRevealAt(DAY1)));
        return new Family(groupId, dq, members, tokens);
    }

    private static String text(String who) {
        return who + "의 답변입니다. " + "가".repeat(30);
    }

    private String dbStatus(Family f) {
        return jdbc.queryForObject("select reveal_status from daily_questions where id = ?", String.class, f.dqId());
    }

    private int putStatus(Family f, UUID user, String text) throws Exception {
        return mockMvc.perform(put("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId() + "/answers/me")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(objectMapper.writeValueAsString(java.util.Map.of("textContent", text))))
            .andReturn().getResponse().getStatus();
    }

    private void answer(Family f, UUID user) throws Exception {
        assertThat(putStatus(f, user, text(user.toString().substring(0, 4)))).isEqualTo(200);
    }

    private JsonNode answers(Family f, UUID viewer) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId() + "/answers")
                .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andReturn();
        return body(r).path("data");
    }

    private String detailRaw(Family f, UUID viewer) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId())
                .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 작업들을 동시에 시작하고 모두 끝날 때까지 기다린다(20초 안에 안 끝나면 교착·영구 대기로 보고 실패).
     * 테스트 전용 트리거가 답변 INSERT 직후 0.7초 멈춰, 먼저 들어온 쪽이 잠금을 쥔 채 상대가 끼어들 시간을 만든다:
     * 잠금이 없으면 두 트랜잭션이 서로의 미커밋 답변을 못 보고(write skew), 순서가 틀리면 교착이 난다.
     * 예외는 버리지 않고 돌려준다(기대한 거절은 호출자가 검사한다).
     */
    @SafeVarargs
    private List<Throwable> runConcurrently(java.util.concurrent.Callable<?>... tasks) throws Exception {
        jdbc.execute("create or replace function test_answer_insert_delay() returns trigger as "
            + "$$ begin perform pg_sleep(0.7); return new; end $$ language plpgsql");
        jdbc.execute("create trigger test_answer_insert_delay after insert on answers "
            + "for each row execute function test_answer_insert_delay()");
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        List<Throwable> failures = new ArrayList<>();
        try {
            start.countDown();
            for (Future<?> fu : futures) {
                try {
                    fu.get(20, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            jdbc.execute("drop trigger if exists test_answer_insert_delay on answers");
        }
        return failures;
    }

    private void assertOnlyAccessDenied(List<Throwable> failures) {
        for (Throwable t : failures) {
            assertThat(t).isInstanceOf(com.ieum.backend.global.exception.BusinessException.class);
            assertThat(((com.ieum.backend.global.exception.BusinessException) t).getErrorCode())
                .isEqualTo(com.ieum.backend.global.exception.ErrorCode.GROUP_ACCESS_DENIED);
        }
    }

    /** 불변식: LOCKED가 남아 있다면 아직 답하지 않은 유효 멤버가 실제로 있어야 한다("모두 답했는데 안 열림"이 없다). */
    private void assertNoMissedReveal(Family f) {
        if (!"LOCKED".equals(dbStatus(f))) {
            return;
        }
        Integer unanswered = jdbc.queryForObject(
            "select count(*) from group_members m join users u on u.id = m.user_id where m.group_id = ? "
                + "and m.status = 'ACTIVE' and u.deleted_at is null "
                + "and not exists (select 1 from answers a where a.daily_question_id = ? and a.user_id = m.user_id)",
            Integer.class, f.groupId(), f.dqId());
        assertThat(unanswered).as("LOCKED인데 미답변 유효 멤버가 없으면 공개가 누락된 것").isPositive();
    }

    // ---------- 수용 기준: 동시 마지막 저장 ----------

    @RepeatedTest(5)
    @DisplayName("두 멤버가 동시에 마지막으로 저장해도 정확히 한 번 REVEALED_ALL로 전환되고 푸시는 멤버당 1회다")
    void concurrentLastSavesRevealExactlyOnce() throws Exception {
        Family f = newFamily(3);
        answer(f, f.members().get(0)); // 남은 사람: 1번, 2번
        at(DAY1, 21, 0);

        runConcurrently(
            () -> answerService.save(f.groupId(), f.dqId(), f.members().get(1), text("동시")),
            () -> answerService.save(f.groupId(), f.dqId(), f.members().get(2), text("동시")));

        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL"); // 공개 누락(write skew)이면 LOCKED로 남는다
        for (String token : f.tokens()) {
            verify(sender, timeout(3000).times(1)).send(eq(token), any());
        }
        Thread.sleep(500);
        for (String token : f.tokens()) {
            verify(sender, times(1)).send(eq(token), any()); // 중복 알림 없음
        }
    }

    // ---------- 수용 기준: 서버 마스킹 ----------

    @Test
    @DisplayName("LOCKED 동안 타인 본문은 목록·상세 응답 어디에도 없고 본인 답변만 보인다")
    void lockedHidesOthersBodiesFromTheResponse() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        UUID mom = f.members().get(0);
        UUID kid = f.members().get(1);
        UUID dad = f.members().get(2);
        String kidText = "아이만 아는 비밀 답변입니다 " + "나".repeat(30);
        assertThat(putStatus(f, kid, kidText)).isEqualTo(200);

        // 답하지 않은 엄마: 목록은 비어 있고, 상세에는 아이가 답했다는 사실만 있다.
        JsonNode momList = answers(f, mom);
        assertThat(momList.path("revealStatus").asText()).isEqualTo("LOCKED");
        assertThat(momList.path("answers")).isEmpty();
        String momDetail = detailRaw(f, mom);
        assertThat(momDetail).doesNotContain("비밀 답변");
        JsonNode members = objectMapper.readTree(momDetail).path("data").path("members");
        assertThat(members).hasSize(3);
        for (JsonNode m : members) {
            assertThat(m.path("hasAnswered").asBoolean()).isEqualTo(m.path("displayName").asText().startsWith("멤버1"));
        }

        // 본인 답변은 보인다. 다른 멤버가 답해도 LOCKED이면 타인 본문은 여전히 없다.
        assertThat(putStatus(f, dad, text("아빠"))).isEqualTo(200);
        JsonNode kidList = answers(f, kid);
        assertThat(kidList.path("answers")).hasSize(1);
        assertThat(kidList.path("answers").get(0).path("textContent").asText()).isEqualTo(kidText.strip());
        assertThat(kidList.path("answers").get(0).path("mine").asBoolean()).isTrue();
        assertThat(objectMapper.writeValueAsString(answers(f, mom))).doesNotContain("아빠의 답변", "비밀 답변");
    }

    // ---------- 수용 기준: 17시 경계, 0명, 첫 답변 ----------

    @Test
    @DisplayName("17시 직전에는 LOCKED, 17시 정각 이후 조회에서 답변이 1명 이상이면 REVEALED_TIMEOUT으로 전환된다")
    void timeoutBoundaryAtFivePm() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(1));

        at(DAY1.plusDays(1), 16, 59);
        assertThat(answers(f, f.members().get(0)).path("revealStatus").asText()).isEqualTo("LOCKED");
        assertThat(dbStatus(f)).isEqualTo("LOCKED");

        at(DAY1.plusDays(1), 17, 0);
        assertThat(answers(f, f.members().get(0)).path("revealStatus").asText()).isEqualTo("REVEALED_TIMEOUT");
        assertThat(dbStatus(f)).isEqualTo("REVEALED_TIMEOUT");
    }

    @Test
    @DisplayName("지연 전환은 목록뿐 아니라 오늘의 질문·질문 상세 조회가 17시 이후 첫 요청이어도 일어난다")
    void timeoutIsAlsoTriggeredByTodayAndDetailReads() throws Exception {
        Family viaToday = newFamily(3);
        Family viaDetail = newFamily(3);
        at(DAY1, 20, 0);
        answer(viaToday, viaToday.members().get(1));
        answer(viaDetail, viaDetail.members().get(1));
        at(DAY1.plusDays(1), 17, 0);

        MvcResult today = mockMvc.perform(get("/api/v1/groups/" + viaToday.groupId() + "/questions/today")
                .header("Authorization", bearer(viaToday.members().get(0))))
            .andExpect(status().isOk()).andReturn();
        assertThat(body(today).path("data").path("revealStatus").asText()).isEqualTo("REVEALED_TIMEOUT");
        assertThat(dbStatus(viaToday)).isEqualTo("REVEALED_TIMEOUT");

        JsonNode detail = objectMapper.readTree(detailRaw(viaDetail, viaDetail.members().get(0))).path("data");
        assertThat(detail.path("revealStatus").asText()).isEqualTo("REVEALED_TIMEOUT");
        assertThat(dbStatus(viaDetail)).isEqualTo("REVEALED_TIMEOUT");
    }

    @Test
    @DisplayName("17시가 지나도 아무도 답하지 않았으면 LOCKED로 남고, 이후 첫 답변이 들어오면 즉시 REVEALED_TIMEOUT이 된다")
    void zeroAnswersStayLockedThenFirstAnswerRevealsImmediately() throws Exception {
        Family f = newFamily(3);
        at(DAY1.plusDays(1), 17, 30);
        assertThat(answers(f, f.members().get(0)).path("revealStatus").asText()).isEqualTo("LOCKED");
        assertThat(dbStatus(f)).isEqualTo("LOCKED");

        at(DAY1.plusDays(1), 18, 0);
        MvcResult r = mockMvc.perform(put("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId() + "/answers/me")
                .header("Authorization", bearer(f.members().get(1)))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(objectMapper.writeValueAsString(java.util.Map.of("textContent", text("아이")))))
            .andExpect(status().isOk()).andReturn();
        assertThat(body(r).path("data").path("revealStatus").asText()).isEqualTo("REVEALED_TIMEOUT");
        assertThat(dbStatus(f)).isEqualTo("REVEALED_TIMEOUT");
    }

    @Test
    @DisplayName("17시 이후 마지막 멤버가 답해 전원 답변이 되면 시간 초과가 아니라 REVEALED_ALL이 이긴다")
    void allAnsweredBeatsTimeout() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));
        at(DAY1.plusDays(1), 18, 0); // 17시 이후, 아직 아무도 조회하지 않아 LOCKED
        assertThat(dbStatus(f)).isEqualTo("LOCKED");

        answer(f, f.members().get(2));

        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
    }

    @Test
    @DisplayName("TIMEOUT 상태에서 답하지 않은 멤버는 타인 답변을 볼 수 없고, 답변한 뒤에는 볼 수 있다")
    void timeoutRevealsOthersOnlyToThoseWhoAnswered() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));
        at(DAY1.plusDays(1), 17, 1);
        UUID late = f.members().get(2);

        JsonNode before = answers(f, late);
        assertThat(before.path("revealStatus").asText()).isEqualTo("REVEALED_TIMEOUT");
        assertThat(before.path("answers")).isEmpty();
        assertThat(detailRaw(f, late)).doesNotContain("의 답변입니다");

        answer(f, late);

        JsonNode after = answers(f, late);
        assertThat(after.path("answers")).hasSize(3);
        assertThat(dbStatus(f)).isEqualTo("REVEALED_TIMEOUT"); // 승격 없음(단방향)
        assertThat(answers(f, f.members().get(0)).path("answers")).hasSize(3); // 먼저 답한 사람은 계속 본다
    }

    // ---------- 수용 기준: 글자 수, 공개 후 수정 ----------

    @Test
    @DisplayName("공백을 빼고 29자인 본문은 길이가 길어도 거절되고, 정확히 30자는 통과한다")
    void whitespaceDoesNotCountTowardMinimum() throws Exception {
        Family f = newFamily(2);
        at(DAY1, 20, 0);
        String padded = "가 ".repeat(29) + "          "; // 글자 29개 + 공백 다수
        assertThat(padded.length()).isGreaterThan(40);
        assertThat(putStatus(f, f.members().get(0), padded)).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from answers where daily_question_id = ?", Integer.class, f.dqId()))
            .isZero();
        assertThat(putStatus(f, f.members().get(0), "가 ".repeat(30))).isEqualTo(200); // 정확히 30자는 통과
    }

    @Test
    @DisplayName("공백 포함 2000자까지 저장되고 2001자는 400으로 거절된다(수정도 동일)")
    void maximumLengthIs2000IncludingWhitespace() throws Exception {
        Family f = newFamily(2);
        at(DAY1, 20, 0);
        UUID mom = f.members().get(0);
        // 2000자: 공백이 섞여 있어도 공백 포함으로 센다(양 끝 공백이 아니므로 strip 영향 없음).
        String max = "가나 ".repeat(666) + "다라"; // 666*3 + 2 = 2000
        assertThat(max.length()).isEqualTo(2000);
        assertThat(putStatus(f, mom, max)).isEqualTo(200);

        assertThat(putStatus(f, mom, max + "마")).isEqualTo(400);
        assertThat(putStatus(f, f.members().get(1), "가".repeat(2001))).isEqualTo(400);
        assertThat(jdbc.queryForObject("select length(text_content) from answers where daily_question_id = ? and user_id = ?",
            Integer.class, f.dqId(), mom)).isEqualTo(2000); // 거절된 수정은 기존 답변을 바꾸지 않는다
        assertThat(jdbc.queryForObject("select count(*) from answers where user_id = ?", Integer.class, f.members().get(1)))
            .isZero();
    }

    @Test
    @DisplayName("수정도 30자 규칙을 적용하고 거절되면 기존 답변이 유지된다. 공개된 뒤에 수정해도 상태는 유지된다")
    void editIsValidatedAndKeepsRevealAfterReveal() throws Exception {
        Family f = newFamily(2);
        at(DAY1, 20, 0);
        UUID mom = f.members().get(0);
        UUID kid = f.members().get(1);
        answer(f, mom);
        assertThat(putStatus(f, mom, "너무 짧음")).isEqualTo(400);
        assertThat(jdbc.queryForObject("select text_content from answers where daily_question_id = ? and user_id = ?",
            String.class, f.dqId(), mom)).contains("의 답변입니다");

        answer(f, kid);
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        String edited = "공개 이후에 수정한 답변입니다 " + "다".repeat(30);
        assertThat(putStatus(f, mom, edited)).isEqualTo(200);

        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        assertThat(jdbc.queryForObject("select count(*) from answers where daily_question_id = ? and user_id = ?",
            Integer.class, f.dqId(), mom)).isEqualTo(1); // 한 사용자 한 건
        assertThat(answers(f, kid).path("answers").toString()).contains("공개 이후에 수정한 답변입니다");
    }

    // ---------- 수용 기준: 접근 권한 ----------

    @Test
    @DisplayName("비멤버와 탈퇴 유예 사용자는 조회·저장이 거절된다")
    void nonMembersAndWithdrawnUsersAreRejected() throws Exception {
        Family f = newFamily(2);
        at(DAY1, 20, 0);
        UUID outsider = newUser("남");
        assertThat(putStatus(f, outsider, text("남"))).isEqualTo(403);
        mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId() + "/answers")
            .header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId())
            .header("Authorization", bearer(outsider))).andExpect(status().isForbidden());

        UUID kid = f.members().get(1);
        jdbc.update("update users set deleted_at = now() where id = ?", kid);
        assertThat(putStatus(f, kid, text("아이"))).isEqualTo(403);
        mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/" + f.dqId() + "/answers")
            .header("Authorization", bearer(kid))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from answers", Integer.class)).isZero();
    }

    @Test
    @DisplayName("다른 그룹의 질문 id로는 저장·조회할 수 없다(404)")
    void questionOfAnotherGroupIsNotFound() throws Exception {
        Family mine = newFamily(2);
        Family other = newFamily(2);
        at(DAY1, 20, 0);
        assertThat(mockMvc.perform(put("/api/v1/groups/" + mine.groupId() + "/questions/" + other.dqId() + "/answers/me")
                .header("Authorization", bearer(mine.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(objectMapper.writeValueAsString(java.util.Map.of("textContent", text("엄마")))))
            .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ---------- 수용 기준: 멤버 변동 재평가 ----------

    @Autowired private com.ieum.backend.domain.group.service.GroupMembershipService membershipService;
    @Autowired private com.ieum.backend.domain.auth.service.AccountService accountService;

    @Test
    @DisplayName("미답변 멤버가 나가면 전원 답변이 성립해 공개되고 푸시는 남은 멤버에게 1회씩이다")
    void leaveOfLastUnansweredMemberRevealsAll() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));
        assertThat(dbStatus(f)).isEqualTo("LOCKED");

        membershipService.leave(f.groupId(), f.members().get(2), null);

        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(0)), any());
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(1)), any());
    }

    @Test
    @DisplayName("강퇴로 분모가 줄어 조건을 충족하면 공개로 전환되고, 충족하지 않으면 LOCKED를 유지한다")
    void removalRevealsOnlyWhenConditionIsMet() throws Exception {
        Family f = newFamily(4);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));

        membershipService.remove(f.groupId(), f.owner(), f.members().get(3)); // 2번이 아직 미답변이라 충족 안 함
        assertThat(dbStatus(f)).isEqualTo("LOCKED");

        membershipService.remove(f.groupId(), f.owner(), f.members().get(2));
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
    }

    @Test
    @DisplayName("탈퇴 요청(유예 진입)으로 분모가 줄어 조건을 충족하면 공개로 전환된다. 복구해도 공개 상태는 되돌아가지 않는다")
    void withdrawalRevealsAll() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));

        accountService.withdraw(f.members().get(2), com.ieum.backend.domain.common.enums.ContentChoice.KEEP);

        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        accountService.restore(f.members().get(2));
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
    }

    @Test
    @DisplayName("지난 질문도 재평가된다: 마감이 없어 LOCKED로 남은 옛 질문이 멤버 변동으로 공개된다")
    void pastLockedQuestionsAreReevaluatedToo() throws Exception {
        Family f = newFamily(3);
        UUID oldDq = UUID.randomUUID();
        LocalDate day0 = DAY1.minusDays(5);
        jdbc.update("insert into daily_questions (id, group_id, question_id, cycle_date, reveal_status, assigned_at, auto_reveal_at) "
                + "values (?, ?, (select id from questions where sequence_order = 2), ?, 'LOCKED', ?, ?)",
            oldDq, f.groupId(), day0,
            java.sql.Timestamp.from(TimeZoneConstants.assignedAt(day0)),
            java.sql.Timestamp.from(TimeZoneConstants.autoRevealAt(day0)));
        for (int i = 0; i < 2; i++) {
            UUID u = f.members().get(i);
            jdbc.update("insert into answers (daily_question_id, group_id, user_id, text_content, input_method) "
                + "values (?, ?, ?, ?, 'TEXT_ONLY')", oldDq, f.groupId(), u, text("옛"));
        }
        at(DAY1, 20, 0);

        membershipService.leave(f.groupId(), f.members().get(2), null);

        assertThat(jdbc.queryForObject("select reveal_status from daily_questions where id = ?", String.class, oldDq))
            .isEqualTo("REVEALED_ALL");
    }

    // ---------- 락 순서: 실제 교착 시나리오 반복(ADR-0007 결정 9) ----------

    @RepeatedTest(5)
    @DisplayName("교착 없음: 마지막 저장 ‖ 다른 멤버 나가기 — 최종적으로 공개되고 푸시는 한 번이다")
    void saveRacingWithLeave() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        UUID saver = f.members().get(2);
        UUID leaver = f.members().get(1);

        List<Throwable> failures = runConcurrently(
            () -> answerService.save(f.groupId(), f.dqId(), saver, text("저장")),
            () -> { membershipService.leave(f.groupId(), leaver, null); return null; });

        assertThat(failures).isEmpty();
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(0)), any());
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(2)), any());
    }

    @RepeatedTest(5)
    @DisplayName("교착 없음: 마지막 저장 ‖ 본인 탈퇴 요청 — 어느 쪽이 먼저여도 공개된다(탈퇴가 먼저면 저장은 거절)")
    void saveRacingWithOwnWithdrawal() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));
        UUID me = f.members().get(2);

        List<Throwable> failures = runConcurrently(
            () -> answerService.save(f.groupId(), f.dqId(), me, text("저장")),
            () -> { accountService.withdraw(me, com.ieum.backend.domain.common.enums.ContentChoice.KEEP); return null; });

        assertOnlyAccessDenied(failures);
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(0)), any());
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(1)), any());
    }

    @RepeatedTest(5)
    @DisplayName("교착 없음: 마지막 저장 ‖ 다른 멤버 탈퇴 요청 — 어느 쪽이 먼저여도 공개된다")
    void saveRacingWithOtherMemberWithdrawal() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        UUID saver = f.members().get(1);
        UUID withdrawer = f.members().get(2);

        List<Throwable> failures = runConcurrently(
            () -> answerService.save(f.groupId(), f.dqId(), saver, text("저장")),
            () -> { accountService.withdraw(withdrawer, com.ieum.backend.domain.common.enums.ContentChoice.KEEP); return null; });

        assertThat(failures).isEmpty();
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(0)), any());
        verify(sender, timeout(3000).times(1)).send(eq(f.tokens().get(1)), any());
    }

    @RepeatedTest(5)
    @DisplayName("교착 없음: 마지막 저장 ‖ 입장 승인 — 어느 쪽이 먼저여도 공개 누락이 없다")
    void saveRacingWithApproval() throws Exception {
        Family f = newFamily(3);
        at(DAY1, 20, 0);
        answer(f, f.members().get(0));
        answer(f, f.members().get(1));
        UUID joiner = newUser("새멤버");
        jdbc.update("insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at, requested_at) "
            + "values (?, ?, 'CHILD', '새멤버', 'PENDING', now(), now())", f.groupId(), joiner);
        UUID saver = f.members().get(2);

        List<Throwable> failures = runConcurrently(
            () -> answerService.save(f.groupId(), f.dqId(), saver, text("저장")),
            () -> { membershipService.approve(f.groupId(), f.owner(), joiner); return null; });

        assertThat(failures).isEmpty();
        assertNoMissedReveal(f); // 승인이 먼저면 새 멤버가 미답변이라 LOCKED가 맞고, 저장이 먼저면 ALL이 맞다
    }
}
