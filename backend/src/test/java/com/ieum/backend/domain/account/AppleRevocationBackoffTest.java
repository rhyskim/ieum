package com.ieum.backend.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.backend.domain.account.apple.AppleRevocationBackoff;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppleRevocationBackoffTest {

    private static final Duration BASE = Duration.ofMinutes(10);
    private static final Duration MAX = Duration.ofHours(24);

    private static Duration delay(int attempts, double jitter, double random) {
        return AppleRevocationBackoff.delay(attempts, BASE, MAX, jitter, () -> random);
    }

    @Test
    @DisplayName("지터가 없으면 10분 × 2^(n−1)로 늘어나고 24시간에서 멈춘다")
    void doublesFromTenMinutesAndCapsAtOneDay() {
        // random=0.5는 편차 0이다.
        assertThat(delay(1, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(delay(2, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(20));
        assertThat(delay(3, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(40));
        assertThat(delay(4, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(80));
        assertThat(delay(7, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(640));
        assertThat(delay(8, 0.2, 0.5)).isEqualTo(Duration.ofMinutes(1280));
        assertThat(delay(9, 0.2, 0.5)).isEqualTo(MAX);
        assertThat(delay(1000, 0.2, 0.5)).isEqualTo(MAX); // 큰 횟수에서도 넘치지 않는다
    }

    @Test
    @DisplayName("지터는 ±20% 범위 안이다")
    void jitterStaysWithinTwentyPercent() {
        assertThat(delay(1, 0.2, 0.0)).isEqualTo(Duration.ofMinutes(8));
        assertThat(delay(1, 0.2, 1.0)).isEqualTo(Duration.ofMinutes(12));
        assertThat(delay(3, 0.2, 0.0)).isEqualTo(Duration.ofMinutes(32));
        assertThat(delay(3, 0.2, 1.0)).isEqualTo(Duration.ofMinutes(48));
    }

    @Test
    @DisplayName("실패 횟수가 1 미만이면 거부한다")
    void rejectsZeroAttempts() {
        assertThatThrownBy(() -> delay(0, 0.2, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
