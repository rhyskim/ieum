package com.ieum.backend.domain.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 탈퇴 파기·Apple 해지 정책 수치(ADR-0002). 모두 설정값이다. */
@ConfigurationProperties(prefix = "ieum.account")
public record AccountProperties(
    /** 파기 시작: 탈퇴 후 이 일수가 지난 계정부터(복구 마감 30일보다 1일 늦다). */
    Integer purgeAfterDays,
    /** 처리 중 임대: purge_started_at 이후 이 시간이 지나야 다른 실행이 이어받는다. */
    Duration purgeLease,
    /** 한 번의 배치에서 처리할 최대 사용자 수. */
    Integer purgeBatchSize,
    /** Apple 해지 작업이 한 번에 집는 행 수. */
    Integer appleBatchSize,
    /** Apple 해지 작업이 행을 집은 뒤 결과를 반영하기 전까지의 임대. */
    Duration appleLease,
    Duration appleBaseDelay,
    Duration appleMaxDelay,
    /** 이 횟수만큼 실패하면 FAILED로 두고 재시도를 멈춘다. */
    Integer appleMaxAttempts,
    /** 재시도 간격에 더하는 ± 편차 비율(0.2 = ±20%). */
    Double appleJitter
) {
    public AccountProperties {
        purgeAfterDays = purgeAfterDays != null ? purgeAfterDays : 31;
        purgeLease = purgeLease != null ? purgeLease : Duration.ofMinutes(30);
        purgeBatchSize = purgeBatchSize != null ? purgeBatchSize : 100;
        appleBatchSize = appleBatchSize != null ? appleBatchSize : 20;
        appleLease = appleLease != null ? appleLease : Duration.ofMinutes(5);
        appleBaseDelay = appleBaseDelay != null ? appleBaseDelay : Duration.ofMinutes(10);
        appleMaxDelay = appleMaxDelay != null ? appleMaxDelay : Duration.ofHours(24);
        appleMaxAttempts = appleMaxAttempts != null ? appleMaxAttempts : 8;
        appleJitter = appleJitter != null ? appleJitter : 0.2;
    }
}
