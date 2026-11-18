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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.notification.push.PushMessage;
import com.ieum.backend.domain.notification.push.PushResult;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 입장 요청·승인·거절 푸시: 커밋 후 비동기 발송, 실패 격리, 토큰 정리, 제한 재시도(ADR-0004). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {"ieum.push.max-attempts=3", "ieum.push.retry-backoff=1ms"})
class PushNotificationIntegrationTest {

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
    private PushSender sender;

    @BeforeEach
    void resetSender() {
        reset(sender);
        when(sender.send(anyString(), any())).thenReturn(PushResult.SENT);
    }

    // ---------- helpers ----------

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

    private String issueCode(UUID owner, UUID groupId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/groups/" + groupId + "/invite-code")
                .header("Authorization", bearer(owner)))
            .andExpect(status().isOk())
            .andReturn();
        return body(r).path("data").path("code").asText();
    }

    private MvcResult join(UUID user, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/join")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"code\":\"" + code + "\",\"nickname\":\"막내\"}"))
            .andReturn();
    }

    private int decide(UUID actor, UUID groupId, UUID target, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/join-requests/" + target + "/" + action)
                .header("Authorization", bearer(actor)))
            .andReturn().getResponse().getStatus();
    }

    private int tokenRows(String token) {
        return jdbc.queryForObject("select count(*) from user_devices where fcm_token = ?", Integer.class, token);
    }

    private void awaitTokenGone(String token) throws InterruptedException {
        for (int i = 0; i < 60 && tokenRows(token) > 0; i++) {
            Thread.sleep(50);
        }
    }

    private void settle() throws InterruptedException {
        Thread.sleep(500); // 비동기 발송이 "일어나지 않음"을 확인하기 위한 유예
    }

    // ---------- 수용 기준 ----------

    @Test
    @DisplayName("입장 요청 시 방장의 모든 기기에 발송하고, 문구·data에는 개인정보가 없다")
    void joinRequestNotifiesAllOwnerDevicesWithoutPersonalInfo() throws Exception {
        UUID owner = newUser("엄마계정");
        UUID joiner = newUser("막내계정");
        String t1 = addDevice(owner);
        String t2 = addDevice(owner);
        UUID groupId = createGroup(owner);

        assertThat(join(joiner, issueCode(owner, groupId)).getResponse().getStatus()).isEqualTo(200);

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(3000)).send(eq(t1), captor.capture());
        verify(sender, timeout(3000)).send(eq(t2), any());
        PushMessage message = captor.getValue();
        assertThat(message.data()).containsOnlyKeys("type", "groupId")
            .containsEntry("type", "JOIN_REQUESTED").containsEntry("groupId", groupId.toString());
        String visible = message.title() + message.body();
        assertThat(visible).doesNotContain("엄마", "막내", "계정", "우리 가족");
    }

    @Test
    @DisplayName("발송기가 예외를 던져도 입장 요청은 성공하고 PENDING으로 저장된다")
    void sendFailureDoesNotBreakJoinRequest() throws Exception {
        UUID owner = newUser("엄마");
        UUID joiner = newUser("막내");
        addDevice(owner);
        UUID groupId = createGroup(owner);
        when(sender.send(anyString(), any())).thenThrow(new IllegalStateException("fcm down"));

        MvcResult result = join(joiner, issueCode(owner, groupId));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(sender, timeout(3000)).send(anyString(), any());
        assertThat(jdbc.queryForObject(
            "select status from group_members where group_id = ? and user_id = ?",
            String.class, groupId, joiner)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("UNREGISTERED 응답이면 그 토큰만 삭제하고 나머지 기기에는 계속 발송한다")
    void unregisteredTokenIsDeleted() throws Exception {
        UUID owner = newUser("엄마");
        UUID joiner = newUser("막내");
        String dead = addDevice(owner);
        String alive = addDevice(owner);
        UUID groupId = createGroup(owner);
        when(sender.send(eq(dead), any())).thenReturn(PushResult.UNREGISTERED);

        join(joiner, issueCode(owner, groupId));

        awaitTokenGone(dead);
        assertThat(tokenRows(dead)).isZero();
        assertThat(tokenRows(alive)).isEqualTo(1);
        verify(sender, timeout(3000)).send(eq(alive), any());
    }

    @Test
    @DisplayName("일시 오류는 최대 시도 횟수 안에서 재시도해 성공하면 멈추고, 계속 실패해도 토큰은 지우지 않는다")
    void transientErrorsAreRetriedWithLimit() throws Exception {
        UUID owner = newUser("엄마");
        UUID joiner = newUser("막내");
        String flaky = addDevice(owner);
        UUID groupId = createGroup(owner);
        when(sender.send(eq(flaky), any()))
            .thenReturn(PushResult.RETRYABLE, PushResult.RETRYABLE, PushResult.SENT);

        join(joiner, issueCode(owner, groupId));
        verify(sender, timeout(3000).times(3)).send(eq(flaky), any());
        settle();
        verify(sender, times(3)).send(eq(flaky), any());

        // 계속 실패하면 max-attempts(3)에서 포기한다
        reset(sender);
        when(sender.send(anyString(), any())).thenReturn(PushResult.RETRYABLE);
        UUID joiner2 = newUser("둘째");
        join(joiner2, issueCode(owner, groupId));
        verify(sender, timeout(3000).times(3)).send(eq(flaky), any());
        settle();
        verify(sender, times(3)).send(eq(flaky), any());
        assertThat(tokenRows(flaky)).isEqualTo(1);
    }

    @Test
    @DisplayName("승인·거절하면 요청자의 기기에 각각 JOIN_APPROVED, JOIN_REJECTED를 발송한다")
    void approveAndRejectNotifyRequester() throws Exception {
        UUID owner = newUser("엄마");
        UUID kid1 = newUser("막내");
        UUID kid2 = newUser("둘째");
        String kid1Token = addDevice(kid1);
        String kid2Token = addDevice(kid2);
        UUID groupId = createGroup(owner);
        String code = issueCode(owner, groupId);
        join(kid1, code);
        join(kid2, code);

        assertThat(decide(owner, groupId, kid1, "approve")).isEqualTo(200);
        assertThat(decide(owner, groupId, kid2, "reject")).isEqualTo(200);

        ArgumentCaptor<PushMessage> approved = ArgumentCaptor.forClass(PushMessage.class);
        ArgumentCaptor<PushMessage> rejected = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(3000)).send(eq(kid1Token), approved.capture());
        verify(sender, timeout(3000)).send(eq(kid2Token), rejected.capture());
        assertThat(approved.getValue().data()).containsEntry("type", "JOIN_APPROVED")
            .containsEntry("groupId", groupId.toString()).hasSize(2);
        assertThat(rejected.getValue().data()).containsEntry("type", "JOIN_REJECTED")
            .containsEntry("groupId", groupId.toString()).hasSize(2);
    }

    @Test
    @DisplayName("업무 처리가 롤백(방장 아님 403)되면 알림은 나가지 않는다")
    void noPushWhenBusinessTransactionFails() throws Exception {
        UUID owner = newUser("엄마");
        UUID kid = newUser("막내");
        UUID stranger = newUser("남");
        String kidToken = addDevice(kid);
        UUID groupId = createGroup(owner);
        join(kid, issueCode(owner, groupId));

        assertThat(decide(stranger, groupId, kid, "approve")).isEqualTo(403);

        settle();
        verify(sender, never()).send(eq(kidToken), any());
    }
}
