package com.ieum.backend.domain.group.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 그룹 초대 정책 수치(ADR-0003). 모두 설정값이다. */
@ConfigurationProperties(prefix = "ieum.group")
public record GroupProperties(
    Duration inviteTtl,
    Duration pendingTtl
) {
    public GroupProperties {
        if (inviteTtl == null) {
            inviteTtl = Duration.ofMinutes(5);
        }
        if (pendingTtl == null) {
            pendingTtl = Duration.ofDays(2);
        }
    }
}
