package com.ieum.backend.global.health;

import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.common.TimeZoneConstants;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthCheckController {

    private final String applicationName;
    private final Environment environment;

    public HealthCheckController(
        @Value("${spring.application.name:ieum-backend}") String applicationName,
        Environment environment
    ) {
        this.applicationName = applicationName;
        this.environment = environment;
    }

    @GetMapping
    public ApiResponse<HealthCheckResponse> checkHealth() {
        String[] activeProfiles = environment.getActiveProfiles();
        String profile = activeProfiles.length > 0 ? activeProfiles[0] : "default";
        HealthCheckResponse response = new HealthCheckResponse(
            "UP",
            applicationName,
            profile,
            TimeZoneConstants.KST.getId(),
            Instant.now()
        );
        return ApiResponse.ok(response);
    }
}
