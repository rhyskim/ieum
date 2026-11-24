package com.ieum.backend.global.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidOrderTest {

    private static final UUID LOW = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000000");

    @Test
    @DisplayName("최상위 비트가 다른 id도 PostgreSQL처럼 부호 없이 비교한다(7fff… 먼저, 80000000… 나중)")
    void ordersLikeDatabase() {
        assertThat(List.of(HIGH, LOW).stream().sorted(UuidOrder.DB_ORDER).toList()).containsExactly(LOW, HIGH);
        assertThat(UuidOrder.DB_ORDER.compare(LOW, HIGH)).isNegative();
        assertThat(UuidOrder.DB_ORDER.compare(HIGH, HIGH)).isZero();
        // 자바 자연 순서는 반대라는 것이 이 클래스가 존재하는 이유다.
        assertThat(LOW.compareTo(HIGH)).isPositive();
    }

    @Test
    @DisplayName("상위 64비트가 같으면 하위 64비트를 부호 없이 비교한다")
    void comparesLowerBitsUnsigned() {
        UUID a = UUID.fromString("11111111-1111-1111-7fff-ffffffffffff");
        UUID b = UUID.fromString("11111111-1111-1111-8000-000000000000");
        assertThat(UuidOrder.DB_ORDER.compare(a, b)).isNegative();
    }
}
