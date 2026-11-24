package com.ieum.backend.global.common;

import java.util.Comparator;
import java.util.UUID;

/**
 * PostgreSQL의 uuid 정렬(ORDER BY id)과 같은 순서.
 * <p>
 * {@link UUID#compareTo}는 상위·하위 64비트를 부호 있는 long으로 비교하므로 최상위 비트가 다른 id(예: 7fff…와 80000000…)에서
 * DB와 순서가 반대가 된다. 행 락을 id 오름차순으로 잡는 규칙(ADR-0007 결정 9)은 DB 순서를 기준으로 하므로,
 * 자바에서 락 순서를 정할 때는 반드시 이 비교기를 쓴다.
 */
public final class UuidOrder {

    /** 바이트 단위 부호 없는 비교 = PostgreSQL uuid 비교. */
    public static final Comparator<UUID> DB_ORDER = (a, b) -> {
        int hi = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return hi != 0 ? hi : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private UuidOrder() {
    }
}
