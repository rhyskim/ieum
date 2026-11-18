package com.ieum.backend.domain.account.apple;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** 재시도 간격 계산: min(base × 2^(n−1), max)에 ±jitter 비율의 균등 편차를 더한다(ADR-0002 결정 13). */
public final class AppleRevocationBackoff {

    private AppleRevocationBackoff() {
    }

    /**
     * @param failedAttempts 지금까지 실패한 횟수 n(1 이상). 첫 실패 뒤 대기는 base다.
     * @param random 0 이상 1 미만의 난수 공급자(테스트에서 고정값을 넣는다)
     */
    public static Duration delay(
        int failedAttempts,
        Duration base,
        Duration max,
        double jitter,
        DoubleSupplier random
    ) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts는 1 이상이어야 한다");
        }
        // 지수가 커져도 넘치지 않도록 상한을 먼저 확인하며 키운다.
        long baseMillis = base.toMillis();
        long maxMillis = max.toMillis();
        long millis = baseMillis;
        for (int i = 1; i < failedAttempts && millis < maxMillis; i++) {
            millis = Math.min(millis * 2, maxMillis);
        }
        millis = Math.min(millis, maxMillis);
        double factor = 1.0 + jitter * (2.0 * random.getAsDouble() - 1.0);
        return Duration.ofMillis(Math.max(0L, Math.round(millis * factor)));
    }
}
