package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.group.config.GroupProperties;
import com.ieum.backend.global.exception.BusinessException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 입장 시도 제한(ADR-0003 결정 3·10). 사용자당 상태 행을 FOR UPDATE로 잠가 같은 사용자의 동시 시도를
 * 줄 세우고, 순서는 잠금 확인 → 코드 검증 → 실패 기록이다. 실패 기록은 예외를 던지기 전에 커밋한다.
 * 5분 창 만료와 잠금 해제는 다음 시도에서 시각을 비교해 계산한다(배치 없음).
 * 성공해도 fail_count·lock_count는 리셋하지 않는다(자기 그룹 코드로 성공을 끼워 넣는 우회 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JoinAttemptGuard {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final GroupProperties properties;

    /** 이 메서드는 호출자의 트랜잭션 밖에서 호출해야 한다(실패 기록이 롤백되면 안 된다). */
    public <T> T attempt(UUID userId, Supplier<T> verifier) {
        Outcome<T> outcome = tx.execute(status -> {
            Instant now = Instant.now();
            jdbc.update("insert into join_attempt_state (user_id) values (?) on conflict do nothing", userId);
            State state = jdbc.queryForObject(
                """
                select window_started_at, fail_count, locked_until, lock_count, lock_count_started_at
                from join_attempt_state where user_id = ? for update
                """,
                (rs, i) -> new State(
                    toInstant(rs.getTimestamp("window_started_at")),
                    rs.getInt("fail_count"),
                    toInstant(rs.getTimestamp("locked_until")),
                    rs.getInt("lock_count"),
                    toInstant(rs.getTimestamp("lock_count_started_at"))),
                userId);

            if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
                return Outcome.locked(Duration.between(now, state.lockedUntil()));
            }
            try {
                return Outcome.ok(verifier.get());
            } catch (BusinessException e) {
                recordFailure(userId, state, now);
                return Outcome.failed(e);
            }
        });

        if (outcome.lockedFor() != null) {
            throw new JoinLockedException(outcome.lockedFor());
        }
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.value();
    }

    private void recordFailure(UUID userId, State state, Instant now) {
        Instant windowStart = state.windowStartedAt();
        int failCount = state.failCount();
        // 여기까지 왔다면 잠금 중이 아니다. 이미 상한에 닿았던 횟수는 잠금을 치른 것이므로 새 창으로 센다.
        if (windowStart == null
            || !windowStart.plus(properties.joinFailureWindow()).isAfter(now)
            || failCount >= properties.joinMaxFailures()) {
            windowStart = now;
            failCount = 0;
        }
        failCount++;

        Instant lockedUntil = state.lockedUntil();
        int lockCount = state.lockCount();
        Instant lockCountStart = state.lockCountStartedAt();
        boolean locked = failCount >= properties.joinMaxFailures();
        if (locked) {
            lockedUntil = now.plus(properties.joinLockDuration());
            if (lockCountStart == null || !lockCountStart.plus(properties.anomalyWindow()).isAfter(now)) {
                lockCountStart = now;
                lockCount = 0;
            }
            lockCount++;
        }

        jdbc.update(
            """
            update join_attempt_state
               set window_started_at = ?, fail_count = ?, locked_until = ?,
                   lock_count = ?, lock_count_started_at = ?, updated_at = now()
             where user_id = ?
            """,
            Timestamp.from(windowStart), failCount, toTimestamp(lockedUntil),
            lockCount, toTimestamp(lockCountStart), userId);

        if (locked && lockCount >= properties.anomalyLockCount()) {
            String detail = "입장 시도 잠금 " + lockCount + "회 누적(" + properties.anomalyWindow() + " 이내)";
            jdbc.update(
                "insert into security_events (user_id, event_type, detail) values (?, 'JOIN_ATTEMPT_ANOMALY', ?)",
                userId, detail);
            log.error("이상 사용자 감지: userId={}, {}", userId, detail);
        }
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp toTimestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private record State(
        Instant windowStartedAt, int failCount, Instant lockedUntil, int lockCount, Instant lockCountStartedAt
    ) {
    }

    private record Outcome<T>(T value, BusinessException failure, Duration lockedFor) {

        static <T> Outcome<T> ok(T value) {
            return new Outcome<>(value, null, null);
        }

        static <T> Outcome<T> failed(BusinessException e) {
            return new Outcome<>(null, e, null);
        }

        static <T> Outcome<T> locked(Duration remaining) {
            return new Outcome<>(null, null, remaining);
        }
    }
}
