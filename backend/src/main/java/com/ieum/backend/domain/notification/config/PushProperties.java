package com.ieum.backend.domain.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 푸시 발송 정책(ADR-0004). 일시 오류만 재시도하며 횟수·간격은 설정값이다. */
@ConfigurationProperties(prefix = "ieum.push")
public record PushProperties(
    Integer maxAttempts,
    Duration retryBackoff,
    Integer poolSize,
    Integer queueCapacity
) {
    public PushProperties {
        maxAttempts = maxAttempts != null ? maxAttempts : 3;
        retryBackoff = retryBackoff != null ? retryBackoff : Duration.ofMillis(500);
        poolSize = poolSize != null ? poolSize : 4;
        queueCapacity = queueCapacity != null ? queueCapacity : 200;
    }
}
