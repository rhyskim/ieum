package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.notification.push.NoopPushSender;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class DeviceApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private PushSender pushSender;
    @PersistenceContext
    private EntityManager em;

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

    private org.springframework.test.web.servlet.ResultActions register(UUID user, String token, String os)
        throws Exception {
        return mockMvc.perform(post("/api/v1/devices")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .characterEncoding(StandardCharsets.UTF_8)
            .content("{\"fcmToken\":\"" + token + "\",\"deviceOs\":\"" + os + "\"}"));
    }

    private int deviceRows(UUID user, String token) {
        return jdbc.queryForObject(
            "select count(*) from user_devices where user_id = ? and fcm_token = ?",
            Integer.class, user, token);
    }

    private void logout(UUID user, String body) throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("같은 토큰을 두 번 등록해도 1행이고 last_active_at만 갱신된다")
    void registerSameTokenTwiceKeepsOneRow() throws Exception {
        UUID user = newUser("device-a");
        String token = "tok-" + UUID.randomUUID();

        register(user, token, "ANDROID").andExpect(status().isOk());
        var first = jdbc.queryForObject(
            "select last_active_at from user_devices where user_id = ? and fcm_token = ?",
            java.sql.Timestamp.class, user, token);
        Thread.sleep(20);
        register(user, token, "ANDROID").andExpect(status().isOk());

        assertThat(deviceRows(user, token)).isEqualTo(1);
        var second = jdbc.queryForObject(
            "select last_active_at from user_devices where user_id = ? and fcm_token = ?",
            java.sql.Timestamp.class, user, token);
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("잘못된 등록 요청(빈 토큰, 알 수 없는 OS)은 400이고 인증 없이는 401이다")
    void registerValidatesAndRequiresAuth() throws Exception {
        UUID user = newUser("device-b");
        register(user, "", "ANDROID").andExpect(status().isBadRequest());
        register(user, "tok-" + UUID.randomUUID(), "WINDOWS").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"x\",\"deviceOs\":\"ANDROID\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 기기에서 다른 계정이 등록하면 토큰 소유가 이전되어 이전 계정 행은 사라진다(ADR-0004)")
    void registeringSameTokenTransfersOwnership() throws Exception {
        UUID mom = newUser("device-mom");
        UUID daughter = newUser("device-daughter");
        String token = "tok-" + UUID.randomUUID();

        register(mom, token, "ANDROID").andExpect(status().isOk());
        register(daughter, token, "IOS").andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
            "select count(*) from user_devices where fcm_token = ?", Integer.class, token)).isEqualTo(1);
        assertThat(deviceRows(daughter, token)).isEqualTo(1);
        assertThat(deviceRows(mom, token)).isZero();
    }

    @Test
    @DisplayName("DB 제약 UNIQUE(fcm_token)이 최종 방어선이다: 같은 토큰을 다른 사용자 행으로 직접 넣을 수 없다")
    void databaseRejectsSameTokenForTwoUsers() {
        UUID a = newUser("device-uq-a");
        UUID b = newUser("device-uq-b");
        String token = "tok-" + UUID.randomUUID();
        String sql = "insert into user_devices (user_id, fcm_token, device_os) values (?, ?, 'ANDROID')";
        jdbc.update(sql, a, token);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, b, token))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    @DisplayName("로그아웃은 본인 소유의 해당 토큰만 해제하고, 소유가 이전된 토큰이나 다른 기기는 건드리지 않는다")
    void logoutReleasesOnlyOwnToken() throws Exception {
        UUID owner = newUser("device-c");
        UUID other = newUser("device-d");
        String token = "tok-" + UUID.randomUUID();
        String keep = "tok-" + UUID.randomUUID();
        String moved = "tok-" + UUID.randomUUID();
        register(owner, token, "ANDROID").andExpect(status().isOk());
        register(owner, keep, "IOS").andExpect(status().isOk());
        register(owner, moved, "ANDROID").andExpect(status().isOk());
        register(other, moved, "ANDROID").andExpect(status().isOk()); // moved는 other 소유가 됨

        // owner가 옛 기기 토큰(moved)으로 로그아웃을 요청해도 새 소유자(other)의 행은 그대로다
        logout(owner, "{\"refreshToken\":\"none\",\"fcmToken\":\"" + moved + "\"}");
        assertThat(deviceRows(other, moved)).isEqualTo(1);

        // 본인 기기 로그아웃은 해당 토큰만 지운다(같은 계정의 다른 기기는 유지)
        logout(owner, "{\"refreshToken\":\"none\",\"fcmToken\":\"" + token + "\"}");
        assertThat(deviceRows(owner, token)).isZero();
        assertThat(deviceRows(owner, keep)).isEqualTo(1);

        // fcmToken 없이도 로그아웃은 기존처럼 동작한다
        logout(owner, "{\"refreshToken\":\"none\"}");
        assertThat(deviceRows(owner, keep)).isEqualTo(1);
    }

    @Test
    @DisplayName("Firebase 키가 없는 환경에서도 앱이 기동하고 발송기는 아무것도 보내지 않는 no-op이다")
    void appStartsWithoutFirebaseAndSenderIsNoop() {
        assertThat(pushSender).isInstanceOf(NoopPushSender.class);
        assertThat(pushSender.send("any-token", new com.ieum.backend.domain.notification.push.PushMessage(
            "제목", "본문", java.util.Map.of())))
            .isEqualTo(com.ieum.backend.domain.notification.push.PushResult.SKIPPED);
    }
}
