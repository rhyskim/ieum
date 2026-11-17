package com.ieum.backend.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ieum.auth")
public record JwtProperties(
    String jwtSecret,
    Duration accessTtl,
    Duration refreshTtl,
    Duration refreshReuseLeeway
) {
    public JwtProperties {
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(30);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(60);
        }
        if (refreshReuseLeeway == null) {
            refreshReuseLeeway = Duration.ofSeconds(10);
        }
    }
}
