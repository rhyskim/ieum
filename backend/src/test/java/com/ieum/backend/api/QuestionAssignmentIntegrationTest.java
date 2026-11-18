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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.notification.push.PushMessage;
import com.ieum.backend.domain.notification.push.PushResult;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.question.service.QuestionAssignmentService;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/** 질문 은행·보정형 일일 배정과 오늘의 질문 조회(ADR-0006). 시각은 주입한 Clock으로 조작한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class QuestionAssignmentIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 19);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    /** 테스트가 시각을 옮길 수 있는 Clock. withZone으로 만든 뷰도 같은 시각을 따라간다. */
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
    @Autowired private QuestionAssignmentService assignment;
    @Autowired private MutableClock clock;
    @PersistenceContext private EntityManager em;
    @MockBean private PushSender sender;

    @BeforeEach
    void resetState() {
        reset(sender);
        when(sender.send(anyString(), any())).thenReturn(PushResult.SENT);
        jdbc.update("delete from daily_questions");
        jdbc.update("update family_groups set status = 'ARCHIVED'");
        jdbc.update("delete from questions");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("insert into questions (sequence_order, category, question_text) values (?, 'DAILY', ?)", i, "테스트 질문 " + i);
        }
    }

    // ---------- helpers ----------

    private void at(LocalDate date, int hour, int minute) {
        clock.set(ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), KST).toInstant());
    }

    private UUID newUser(String name) {
        return tx.execute(s -> {
            User user = User.create(name + "-" + UUID.randomUUID() + "@test.dev", name, MemberRole.CHILD);
            em.persist(user);
            return user.getId();
        });
    }

    private String addDevice(UUID user) {
        String token = "tok-" + UUID.randomUUID();
        jdbc.update("insert into user_devices (user_id, fcm_token, device_os) values (?, ?, 'ANDROID')", user, token);
        return token;
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
                .content("{\"name\":\"우리 가족\",\"nickname\":\"엄마\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(body(r).path("data").path("id").asText());
    }

    /** 기기 토큰을 가진 방장 1명짜리 그룹. */
    private record Family(UUID groupId, UUID owner, String token) {}

    private Family newFamily() throws Exception {
        UUID owner = newUser("엄마");
        String token = addDevice(owner);
        return new Family(createGroup(owner), owner, token);
    }

    private int rows(UUID groupId) {
        return jdbc.queryForObject("select count(*) from daily_questions where group_id = ?", Integer.class, groupId);
    }

    private List<Integer> servedOrders(UUID groupId) {
        return jdbc.queryForList("select q.sequence_order from daily_questions d join questions q on q.id = d.question_id "
            + "where d.group_id = ? order by d.cycle_date", Integer.class, groupId);
    }

    private void settle() throws InterruptedException {
        Thread.sleep(500); // 비동기 발송이 "일어나지 않음"을 확인하기 위한 유예
    }

    // ---------- 수용 기준 ----------

    @Test
    @DisplayName("같은 사이클에 작업이 여러 번·동시에 실행돼도 그룹당 daily_questions는 1행이고 푸시도 그룹당 1번이다")
    void concurrentAndRepeatedRunsCreateExactlyOneRowPerGroup() throws Exception {
        List<Family> families = List.of(newFamily(), newFamily(), newFamily());
        at(DAY1, 19, 30);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return assignment.assignCurrentCycle();
            }));
        }
        start.countDown();
        int createdTotal = 0;
        for (Future<Integer> f : futures) {
            createdTotal += f.get();
        }
        pool.shutdown();

        assertThat(createdTotal).isEqualTo(3); // 8개 실행 중 새 행을 만든 횟수의 합은 그룹 수와 같다
        for (Family f : families) {
            assertThat(rows(f.groupId())).isEqualTo(1);
            verify(sender, timeout(3000).times(1)).send(eq(f.token()), any());
        }
        assertThat(assignment.assignCurrentCycle()).isZero(); // 다시 실행해도 변화 없음
        settle();
        for (Family f : families) {
            verify(sender, times(1)).send(eq(f.token()), any());
        }
    }

    @Test
    @DisplayName("서버가 19시에 꺼져 있다가 19:30에 켜지면 누락 그룹이 모두 채워지고, 푸시는 새로 만든 그룹에만 간다")
    void serverDownAtSevenAndBackAtSevenThirtyFillsMissingGroups() throws Exception {
        Family alreadyAssigned = newFamily();
        at(DAY1, 19, 0);
        assertThat(assignment.assignCurrentCycle()).isEqualTo(1); // 정상 가동 중이던 그룹
        verify(sender, timeout(3000).times(1)).send(eq(alreadyAssigned.token()), any());

        Family missed1 = newFamily(); // 서버가 꺼져 있는 동안 배정되지 못한 그룹들
        Family missed2 = newFamily();
        at(DAY1, 19, 30);
        assertThat(assignment.assignCurrentCycle()).isEqualTo(2);

        assertThat(rows(missed1.groupId())).isEqualTo(1);
        assertThat(rows(missed2.groupId())).isEqualTo(1);
        verify(sender, timeout(3000).times(1)).send(eq(missed1.token()), any());
        verify(sender, timeout(3000).times(1)).send(eq(missed2.token()), any());
        settle();
        verify(sender, times(1)).send(eq(alreadyAssigned.token()), any()); // 기존 그룹은 재발송되지 않았다
    }

    @Test
    @DisplayName("19시 전에는 배정하지 않고, 지난 사이클(다음날 새벽)은 보정하지 않는다")
    void doesNotAssignBeforeSevenPmAndSkipsPastCycle() throws Exception {
        Family f = newFamily();
        at(DAY1, 18, 59);
        assertThat(assignment.assignCurrentCycle()).isZero();
        at(DAY1.plusDays(1), 2, 0); // 어제 19시 서버가 꺼졌다가 새벽에 켜짐
        assertThat(assignment.assignCurrentCycle()).isZero();
        assertThat(rows(f.groupId())).isZero();
        settle();
        verify(sender, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("그룹이 받은 질문 수에 따라 sequence_order 순으로 배정하고 비활성 질문은 건너뛴다")
    void assignsInSequenceOrderSkippingInactive() throws Exception {
        jdbc.update("update questions set is_active = false where sequence_order = 2");
        Family early = newFamily();
        at(DAY1, 19, 0);
        assignment.assignCurrentCycle();
        at(DAY1.plusDays(1), 19, 0);
        assignment.assignCurrentCycle();
        Family late = newFamily(); // 3일차에 생긴 그룹은 자기 순번(1번째)부터 받는다
        at(DAY1.plusDays(2), 19, 0);
        assignment.assignCurrentCycle();

        assertThat(servedOrders(early.groupId())).containsExactly(1, 3, 4); // 2번은 비활성이라 건너뜀
        assertThat(servedOrders(late.groupId())).containsExactly(1);
    }

    @Test
    @DisplayName("질문 은행이 비면 그 그룹만 건너뛰고 다른 그룹에는 영향이 없다")
    void exhaustedBankSkipsOnlyThatGroup() throws Exception {
        jdbc.update("delete from questions where sequence_order > 2");
        Family old = newFamily();
        at(DAY1, 19, 0);
        assignment.assignCurrentCycle();
        at(DAY1.plusDays(1), 19, 0);
        assignment.assignCurrentCycle(); // old는 질문 2개를 다 받았다
        Family fresh = newFamily();
        at(DAY1.plusDays(2), 19, 0);

        int created = assignment.assignCurrentCycle();

        assertThat(created).isEqualTo(1);
        assertThat(rows(old.groupId())).isEqualTo(2);
        assertThat(servedOrders(fresh.groupId())).containsExactly(1);
    }

    @Test
    @DisplayName("auto_reveal_at은 배정 날짜 다음날 17:00 KST이고 늦게 배정돼도 그대로다")
    void autoRevealAtIsNextDaySeventeenKstEvenWhenAssignedLate() throws Exception {
        Family f = newFamily();
        at(DAY1, 22, 40); // 늦은 배정

        assignment.assignCurrentCycle();

        Instant reveal = jdbc.queryForObject("select auto_reveal_at from daily_questions where group_id = ?",
            Instant.class, f.groupId());
        assertThat(reveal).isEqualTo(ZonedDateTime.of(DAY1.plusDays(1), java.time.LocalTime.of(17, 0), KST).toInstant());
        Instant assigned = jdbc.queryForObject("select assigned_at from daily_questions where group_id = ?",
            Instant.class, f.groupId());
        assertThat(assigned).isEqualTo(ZonedDateTime.of(DAY1, java.time.LocalTime.of(19, 0), KST).toInstant());
    }

    @Test
    @DisplayName("푸시는 개인정보 없이 type·groupId만 담고 발송 실패가 배정에 영향을 주지 않는다")
    void pushCarriesNoPersonalInfoAndFailureDoesNotAffectAssignment() throws Exception {
        Family f = newFamily();
        when(sender.send(anyString(), any())).thenThrow(new IllegalStateException("fcm down"));
        at(DAY1, 19, 0);

        assertThat(assignment.assignCurrentCycle()).isEqualTo(1);

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(3000)).send(eq(f.token()), captor.capture());
        PushMessage message = captor.getValue();
        assertThat(message.data()).containsOnlyKeys("type", "groupId")
            .containsEntry("type", "QUESTION_ASSIGNED").containsEntry("groupId", f.groupId().toString());
        assertThat(message.title() + message.body()).doesNotContain("엄마", "우리 가족", "테스트 질문");
        assertThat(rows(f.groupId())).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘의 질문 조회: 유효 멤버는 현재 사이클의 질문을 받고, 19시 전에는 전날 사이클이며, 배정 전에는 404")
    void todayQuestionForMember() throws Exception {
        Family f = newFamily();
        at(DAY1, 10, 0);
        mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/today").header("Authorization", bearer(f.owner())))
            .andExpect(status().isNotFound());

        at(DAY1, 19, 5);
        assignment.assignCurrentCycle();
        JsonNode data = body(mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/today")
                .header("Authorization", bearer(f.owner())))
            .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(data.path("questionText").asText()).isEqualTo("테스트 질문 1");
        assertThat(data.path("groupId").asText()).isEqualTo(f.groupId().toString());
        assertThat(data.path("cycleDate").asText()).isEqualTo(DAY1.toString());
        assertThat(data.path("revealStatus").asText()).isEqualTo("LOCKED");
        assertThat(data.path("hasAnswered").asBoolean(true)).isFalse();

        at(DAY1.plusDays(1), 9, 0); // 다음날 아침에도 아직 같은 사이클의 질문이다
        JsonNode next = body(mockMvc.perform(get("/api/v1/groups/" + f.groupId() + "/questions/today")
                .header("Authorization", bearer(f.owner())))
            .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(next.path("dailyQuestionId").asText()).isEqualTo(data.path("dailyQuestionId").asText());
    }

    @Test
    @DisplayName("오늘의 질문 조회: 타 그룹 사용자와 탈퇴 유예 사용자는 거절된다")
    void todayQuestionRejectsNonMembersAndWithdrawnUsers() throws Exception {
        Family f = newFamily();
        Family other = newFamily();
        at(DAY1, 19, 5);
        assignment.assignCurrentCycle();
        String url = "/api/v1/groups/" + f.groupId() + "/questions/today";

        mockMvc.perform(get(url).header("Authorization", bearer(other.owner()))).andExpect(status().isForbidden());
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where id = ?", f.owner());
        mockMvc.perform(get(url).header("Authorization", bearer(f.owner()))).andExpect(status().isForbidden());
    }
}
