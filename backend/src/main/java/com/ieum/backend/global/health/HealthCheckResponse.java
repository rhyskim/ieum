package com.ieum.backend.global.health;

import java.time.Instant;

public record HealthCheckResponse(
    String status,
    String application,
    String profile,
    String timezone,
    Instant serverTime
) {
}
