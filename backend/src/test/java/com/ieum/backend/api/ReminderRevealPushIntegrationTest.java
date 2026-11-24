package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.backend.domain.answer.service.AnswerService;
import com.ieum.backend.domain.answer.service.RevealEvaluator;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.notification.push.PushMessage;
import com.ieum.backend.domain.notification.push.PushResult;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.question.service.ReminderService;
import com.ieum.backend.domain.question.service.TimeoutRevealService;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.TimeZoneConstants;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 15시 리마인드와 17시 시간 초과 공개 푸시(ADR-0004 결정 3, ADR-0007). 시각은 주입한 Clock으로 조작한다. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReminderRevealPushIntegrationTest {

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private AnswerService answerService;
    @Autowired private RevealEvaluator revealEvaluator;
    @Autowired private ReminderService reminderService;
    @Autowired private TimeoutRevealService timeoutRevealService;
    @Autowired private MutableClock clock;
    @PersistenceContext private EntityManager em;
    @MockBean private PushSender sender;

    @BeforeEach
    void resetState() {
        reset(sender);
        when(sender.send(anyString(), any())).thenReturn(PushResult.SENT);
        // 마감 전에서 시작한다: 17시 이후의 첫 답변은 즉시 시간 초과 공개로 이어지므로(ADR-0007 결정 5) 이전 테스트의 시각이 남으면 안 된다.
        at(10, 0);
        jdbc.update("delete from daily_questions");
        jdbc.update("update family_groups set status = 'ARCHIVED'");
        jdbc.update("delete from questions");
        jdbc.update("insert into questions (sequence_order, category, question_text) values (1, 'DAILY', '테스트 질문')");
    }

    /** 비동기 발송이 다음 테스트의 mock 검증에 섞이지 않도록 남은 발송을 기다린다. */
    @AfterEach
    void drainAsyncPushes() {
        sleep(700);
    }

    // ---------- helpers ----------

    /** 마감(DAY1 다음날 17:00 KST) 당일의 시각으로 옮긴다. */
    private void at(int hour, int minute) {
        clock.set(ZonedDateTime.of(DAY1.plusDays(1), LocalTime.of(hour, minute), KST).toInstant());
    }

    private UUID newUser(String name) {
        return tx.execute(s -> {
            User user = User.create(name + "-" + UUID.randomUUID() + "@test.dev", name, MemberRole.CHILD);
            em.persist(user);
            return user.getId();
        });
    }

    /** members[0]은 방장(가족 호칭 "엄마"), 이후는 "멤버i". 각자 기기 토큰 1개. 질문 1개(LOCKED). */
    private record Family(UUID groupId, UUID dqId, List<UUID> members, List<String> tokens) {
        String token(int i) { return tokens.get(i); }
        UUID member(int i) { return members.get(i); }
    }

    private Family newFamily(int size) {
        UUID groupId = UUID.randomUUID();
        jdbc.update("insert into family_groups (id, name, creator_id, status) values (?, '우리 가족', ?, 'ACTIVE')",
            groupId, newUser("creator"));
        List<UUID> members = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            UUID u = newUser(i == 0 ? "엄마" : "멤버" + i);
            members.add(u);
            jdbc.update("insert into group_members (group_id, user_id, member_role, family_nickname, status, joined_at, is_owner) "
                    + "values (?, ?, 'CHILD', ?, 'ACTIVE', now() + (? * interval '1 second'), ?)",
                groupId, u, i == 0 ? "엄마" : "멤버" + i, i, i == 0);
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

    /** 호출 순서대로 답변을 저장한다(created_at이 단조 증가해 "먼저 답한 사람"이 정해진다). */
    private void answer(Family f, int member) {
        answerService.save(f.groupId(), f.dqId(), f.member(member), "비밀 답변 본문입니다. " + "가".repeat(30));
        sleep(5);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void alias(Family f, int viewer, int target, String alias) {
        jdbc.update("insert into member_aliases (group_id, viewer_id, target_id, alias) values (?, ?, ?, ?)",
            f.groupId(), f.member(viewer), f.member(target), alias);
    }

    private String dbStatus(Family f) {
        return jdbc.queryForObject("select reveal_status from daily_questions where id = ?", String.class, f.dqId());
    }

    /** 비동기 발송이 "일어나지 않음"을 확인하기 위한 유예. */
    private void settle() {
        sleep(500);
    }

    private PushMessage pushTo(String token) {
        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(3000).times(1)).send(eq(token), captor.capture());
        return captor.getValue();
    }

    private List<Integer> concurrently(int threads, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> fu : futures) {
            results.add(fu.get());
        }
        pool.shutdown();
        return results;
    }

    // ---------- 리마인드 ----------

    @Test
    @DisplayName("리마인드는 여러 번·동시에(서버 여러 대) 실행돼도 미답변 멤버당 1회이고, 답한 멤버에게는 가지 않는다")
    void reminderSentOncePerRecipientEvenWhenRunConcurrently() throws Exception {
        Family f = newFamily(3);
        answer(f, 1);
        at(15, 0);

        List<Integer> results = concurrently(8, reminderService::remindDue);

        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        PushMessage msg = pushTo(f.token(0));
        pushTo(f.token(2));
        assertThat(msg.data()).containsOnlyKeys("type", "groupId")
            .containsEntry("type", "REMINDER").containsEntry("groupId", f.groupId().toString());
        assertThat(reminderService.remindDue()).isZero();
        settle();
        verify(sender, never()).send(eq(f.token(1)), any());
        verify(sender, times(2)).send(anyString(), any());
    }

    @Test
    @DisplayName("아무도 답하지 않았으면 모든 유효 멤버에게 가고, 탈퇴 유예 멤버에게는 가지 않는다")
    void reminderGoesToAllValidMembersWhenNobodyAnswered() {
        Family f = newFamily(3);
        jdbc.update("update users set deleted_at = now() where id = ?", f.member(2));
        at(15, 0);

        assertThat(reminderService.remindDue()).isEqualTo(1);

        pushTo(f.token(0));
        pushTo(f.token(1));
        settle();
        verify(sender, never()).send(eq(f.token(2)), any());
    }

    @Test
    @DisplayName("이탈한 멤버(LEFT)에게는 리마인드가 가지 않는다")
    void reminderSkipsLeftMembers() {
        Family f = newFamily(3);
        jdbc.update("update group_members set status = 'LEFT' where user_id = ?", f.member(2));
        at(15, 0);

        reminderService.remindDue();

        pushTo(f.token(0));
        pushTo(f.token(1));
        settle();
        verify(sender, never()).send(eq(f.token(2)), any());
    }

    @Test
    @DisplayName("15시에 서버가 꺼져 있다 16:10에 켜지면 한 번 나가고, 17시 이후에는 나가지 않는다")
    void reminderCatchesUpAtSixteenAndNeverAfterDeadline() {
        Family late = newFamily(2);
        at(14, 59);
        assertThat(reminderService.remindDue()).isZero(); // 2시간 창 밖

        at(16, 10);
        assertThat(reminderService.remindDue()).isEqualTo(1);
        pushTo(late.token(0));
        pushTo(late.token(1));

        Family afterDeadline = newFamily(2);
        at(17, 0); // 마감 정각도 보내지 않는다(auto_reveal_at > now)
        assertThat(reminderService.remindDue()).isZero();
        at(17, 1);
        assertThat(reminderService.remindDue()).isZero();
        settle();
        verify(sender, never()).send(eq(afterDeadline.token(0)), any());
        assertThat(jdbc.queryForObject("select reminder_sent from daily_questions where id = ?",
            Boolean.class, afterDeadline.dqId())).isFalse();
    }

    @Test
    @DisplayName("전원 답변으로 이미 REVEALED_ALL인 질문에는 리마인드를 보내지 않는다")
    void noReminderAfterAllAnswered() {
        Family f = newFamily(2);
        answer(f, 0);
        answer(f, 1);
        verify(sender, timeout(3000).times(2)).send(anyString(), any()); // ALL_ANSWERED
        reset(sender);
        at(15, 30);

        assertThat(reminderService.remindDue()).isZero();
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");

        at(17, 5); // 17시 이후 작업과 조회 지연 전환도 REVEALED_ALL을 건드리지 않고 공개 푸시를 보내지 않는다
        timeoutRevealService.revealDue();
        tx.executeWithoutResult(s -> revealEvaluator.revealIfTimedOut(f.dqId(), clock.instant()));

        settle();
        assertThat(dbStatus(f)).isEqualTo("REVEALED_ALL");
        verify(sender, never()).send(anyString(), any());
    }

    // ---------- 17시 공개 ----------

    @Test
    @DisplayName("17시 작업과 조회 시 지연 전환이 경쟁해도 공개 푸시는 멤버당 1회다")
    void jobAndLazyRevealRaceSendsOnce() throws Exception {
        Family f = newFamily(3);
        answer(f, 0);
        at(17, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> job = pool.submit(() -> {
            start.await();
            timeoutRevealService.revealDue();
            return null;
        });
        Future<?> lazy = pool.submit(() -> {
            start.await();
            tx.executeWithoutResult(s -> revealEvaluator.revealIfTimedOut(f.dqId(), clock.instant()));
            return null;
        });
        start.countDown();
        job.get();
        lazy.get();
        pool.shutdown();

        assertThat(dbStatus(f)).isEqualTo("REVEALED_TIMEOUT");
        for (int i = 0; i < 3; i++) {
            assertThat(pushTo(f.token(i)).data()).containsOnlyKeys("type", "groupId")
                .containsEntry("type", "REVEALED_TIMEOUT").containsEntry("groupId", f.groupId().toString());
        }
        timeoutRevealService.revealDue();
        settle();
        verify(sender, times(3)).send(anyString(), any());
    }

    @Test
    @DisplayName("조회 시점 지연 전환이 먼저 전환해도 공개 푸시가 누락되지 않고, 이후 17시 작업은 다시 보내지 않는다")
    void lazyRevealAloneStillNotifies() {
        Family f = newFamily(2);
        answer(f, 0);
        at(17, 1);

        tx.executeWithoutResult(s -> revealEvaluator.revealIfTimedOut(f.dqId(), clock.instant()));
        pushTo(f.token(0));
        pushTo(f.token(1));

        timeoutRevealService.revealDue();
        settle();
        verify(sender, times(2)).send(anyString(), any());
    }

    @Test
    @DisplayName("답변이 0건이면 전환도 푸시도 없고 LOCKED가 유지된다")
    void noAnswersKeepsLocked() {
        Family f = newFamily(2);
        at(17, 5);

        timeoutRevealService.revealDue();
        tx.executeWithoutResult(s -> revealEvaluator.revealIfTimedOut(f.dqId(), clock.instant()));

        settle();
        assertThat(dbStatus(f)).isEqualTo("LOCKED");
        verify(sender, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("전환 트랜잭션이 롤백되면 푸시는 나가지 않고 상태도 그대로이며, 다시 시도하면 나간다")
    void rollbackSendsNothing() {
        Family f = newFamily(2);
        answer(f, 0);
        at(17, 1);

        tx.executeWithoutResult(s -> {
            revealEvaluator.revealIfTimedOut(f.dqId(), clock.instant());
            s.setRollbackOnly();
        });

        settle();
        assertThat(dbStatus(f)).isEqualTo("LOCKED");
        verify(sender, never()).send(anyString(), any());

        timeoutRevealService.revealDue();
        pushTo(f.token(0));
        pushTo(f.token(1));
    }

    // ---------- 문구 ----------

    @Test
    @DisplayName("문구는 수신자별이다: 답한 멤버는 다른 답변자 호칭, 미답변 멤버는 답변자들 호칭과 본인 이름, 호칭은 수신자가 정한 것")
    void messagesArePerRecipient() {
        Family f = newFamily(4);
        answer(f, 1); // 멤버1이 먼저
        answer(f, 2);
        alias(f, 0, 1, "큰딸"); // 방장이 멤버1을 "큰딸"로 부른다
        at(17, 0);

        timeoutRevealService.revealDue();

        // 호칭이 있으면 수신자가 정한 호칭, 없으면 표시 이름 규칙(가족 호칭)
        assertThat(pushTo(f.token(0)).body())
            .isEqualTo("큰딸, 멤버2님은 오늘의 질문에 답을 남겼어요. 엄마님도 답을 남겨보시는 게 어떨까요?");
        assertThat(pushTo(f.token(3)).body())
            .isEqualTo("멤버1, 멤버2님은 오늘의 질문에 답을 남겼어요. 멤버3님도 답을 남겨보시는 게 어떨까요?");
        // 답한 멤버에게는 다른 답변자 중 가장 먼저 답한 사람
        assertThat(pushTo(f.token(1)).body()).isEqualTo("멤버2님은 오늘의 질문에 어떤 답을 했을까요?");
        assertThat(pushTo(f.token(2)).body()).isEqualTo("멤버1님은 오늘의 질문에 어떤 답을 했을까요?");
    }

    @Test
    @DisplayName("다른 답변자가 없으면 일반 문구이고, 3명을 넘으면 '외 N명', data에는 type·groupId만 있고 답변 내용은 없다")
    void genericAndOverflowMessages() {
        Family solo = newFamily(2);
        answer(solo, 0);
        Family many = newFamily(6);
        for (int i = 0; i < 4; i++) {
            answer(many, i);
        }
        at(17, 0);

        timeoutRevealService.revealDue();

        assertThat(pushTo(solo.token(0)).body()).isEqualTo("가족의 답변이 공개됐어요");
        // 다른 답변자가 여럿이면 가장 먼저 답한 사람(엄마)을 쓴다
        assertThat(pushTo(many.token(3)).body()).isEqualTo("엄마님은 오늘의 질문에 어떤 답을 했을까요?");
        PushMessage unanswered = pushTo(many.token(4));
        assertThat(unanswered.body()).isEqualTo(
            "엄마, 멤버1, 멤버2님 외 1명은 오늘의 질문에 답을 남겼어요. 멤버4님도 답을 남겨보시는 게 어떨까요?");
        assertThat(unanswered.data()).containsOnlyKeys("type", "groupId");
        assertThat(unanswered.body() + unanswered.title() + unanswered.data()).doesNotContain("비밀 답변");
    }

    @Test
    @DisplayName("탈퇴 유예 중인 답변자는 호칭에 넣지 않고 푸시 대상에서도 제외한다")
    void withdrawnAnswererIsNotNamed() {
        Family f = newFamily(4);
        answer(f, 1);
        answer(f, 2);
        jdbc.update("update users set deleted_at = now() where id = ?", f.member(1));
        at(17, 0);

        timeoutRevealService.revealDue();

        assertThat(pushTo(f.token(0)).body())
            .isEqualTo("멤버2님은 오늘의 질문에 답을 남겼어요. 엄마님도 답을 남겨보시는 게 어떨까요?");
        assertThat(pushTo(f.token(2)).body()).isEqualTo("가족의 답변이 공개됐어요"); // 다른 유효 답변자가 없다
        settle();
        verify(sender, never()).send(eq(f.token(1)), any());
    }

    @Test
    @DisplayName("호칭에 줄바꿈이나 매우 긴 문자열이 있어도 문구 길이가 제한된다")
    void aliasIsSanitizedAndTruncated() {
        Family f = newFamily(3);
        answer(f, 1);
        alias(f, 0, 1, "큰\n딸\r\n" + "가".repeat(30));
        at(17, 0);

        timeoutRevealService.revealDue();

        String body = pushTo(f.token(0)).body();
        assertThat(body).doesNotContain("\n").doesNotContain("\r");
        assertThat(body).startsWith("큰 딸 " + "가".repeat(6) + "…님은");
        assertThat(body.length()).isLessThan(80);
    }
}
