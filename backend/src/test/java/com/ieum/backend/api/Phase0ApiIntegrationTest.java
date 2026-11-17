package com.ieum.backend.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.global.security.CurrentUser;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class Phase0ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/health 호출 시 200 OK 및 HealthCheckResponse 5개 필드를 반환한다")
    void checkHealthReturnsUpAndKstTimezone() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.application").value("ieum-backend"))
            .andExpect(jsonPath("$.data.profile").value("test"))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.data.serverTime").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/policies 미인증 호출 시 기본 FREE 플랜 정책(180초, 30일 보관)을 반환한다")
    void getEntitlementPoliciesUnauthenticatedReturnsFreePlan() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.currentPlan").value("FREE"))
            .andExpect(jsonPath("$.data.canAttachOriginalVoice").value(true))
            .andExpect(jsonPath("$.data.maxVoiceDurationSeconds").value(180))
            .andExpect(jsonPath("$.data.voiceServerRetentionDays").value(30))
            .andExpect(jsonPath("$.data.canDownloadVoiceToLocal").value(true))
            .andExpect(jsonPath("$.data.sttEngineType").value("ON_DEVICE"));
    }

    @Test
    @DisplayName("GET /api/v1/policies 에 PREMIUM 사용자 인증 컨텍스트 주입 시 PREMIUM 정책(600초, 36500일 보관)을 반환한다")
    void getEntitlementPoliciesWithPremiumUserReturnsPremiumPlan() throws Exception {
        CurrentUser premiumUser = new CurrentUser(UUID.randomUUID(), MembershipTier.PREMIUM);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            premiumUser,
            null,
            Collections.emptyList()
        );

        mockMvc.perform(get("/api/v1/policies").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.currentPlan").value("PREMIUM"))
            .andExpect(jsonPath("$.data.canAttachOriginalVoice").value(true))
            .andExpect(jsonPath("$.data.maxVoiceDurationSeconds").value(600))
            .andExpect(jsonPath("$.data.voiceServerRetentionDays").value(36500))
            .andExpect(jsonPath("$.data.canDownloadVoiceToLocal").value(true))
            .andExpect(jsonPath("$.data.sttEngineType").value("CLOUD_WHISPER"));
    }

    @Test
    @DisplayName("GET /v3/api-docs 호출 시 OpenAPI JSON 명세가 200 OK 로 반환된다")
    void openApiDocsEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("이음 (Ieum) API Server"));
    }

    @Test
    @DisplayName("미인증 상태로 보호된 경로 호출 시 CustomAuthenticationEntryPoint 가 401 UNAUTHORIZED ApiResponse 를 반환한다")
    void protectedEndpointWithoutAuthReturns401ApiResponse() throws Exception {
        mockMvc.perform(get("/api/v1/groups/00000000-0000-0000-0000-000000000001"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("허용되지 않은 HTTP 메서드(DELETE /api/v1/health) 호출 시 405 METHOD_NOT_ALLOWED ApiResponse 를 반환한다")
    void unsupportedHttpMethodReturns405ApiResponse() throws Exception {
        mockMvc.perform(delete("/api/v1/health"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
