package com.ieum.backend.domain.group.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 그룹 초대·입장 정책 수치(ADR-0003). 모두 설정값이다. */
@ConfigurationProperties(prefix = "ieum.group")
public record GroupProperties(
    Duration inviteTtl,
    Duration pendingTtl,
    Integer memberLimit,
    Integer joinMaxFailures,
    Duration joinFailureWindow,
    Duration joinLockDuration,
    Integer anomalyLockCount,
    Duration anomalyWindow
) {
    public GroupProperties {
        inviteTtl = inviteTtl != null ? inviteTtl : Duration.ofMinutes(5);
        pendingTtl = pendingTtl != null ? pendingTtl : Duration.ofDays(2);
        memberLimit = memberLimit != null ? memberLimit : 20;
        joinMaxFailures = joinMaxFailures != null ? joinMaxFailures : 5;
        joinFailureWindow = joinFailureWindow != null ? joinFailureWindow : Duration.ofMinutes(5);
        joinLockDuration = joinLockDuration != null ? joinLockDuration : Duration.ofMinutes(20);
        anomalyLockCount = anomalyLockCount != null ? anomalyLockCount : 3;
        anomalyWindow = anomalyWindow != null ? anomalyWindow : Duration.ofHours(24);
    }
}
