package com.ieum.backend.domain.account.apple;

import com.ieum.backend.domain.account.config.AccountProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Apple 해지 재시도 큐 처리(ADR-0002 결정 13). 파기는 큐에 넣기만 하고, 실제 호출과 재시도는 여기서 한다.
 * 외부 호출 중에는 DB 락을 잡지 않는다: ① 한 문장의 UPDATE로 due 행을 임대(next_attempt_at을 미룸)하며 집고
 * ② 문장 밖에서 호출한 뒤 ③ 결과를 조건부로 반영한다. 서버가 중간에 죽어도 임대가 지나면 다시 집힌다.
 */
@Slf4j
@Service
public class AppleRevocationService {

    private static final int ERROR_MAX = 500;

    private final JdbcTemplate jdbc;
    private final AccountProperties properties;
    private final AppleTokenRevoker revoker;
    private final DoubleSupplier random;

    @Autowired
    public AppleRevocationService(JdbcTemplate jdbc, AccountProperties properties, AppleTokenRevoker revoker) {
        this(jdbc, properties, revoker, () -> ThreadLocalRandom.current().nextDouble());
    }

    AppleRevocationService(
        JdbcTemplate jdbc,
        AccountProperties properties,
        AppleTokenRevoker revoker,
        DoubleSupplier random
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.revoker = revoker;
        this.random = random;
    }

    /** due 행을 한 번 처리하고 집계를 돌려준다. 개별 실패는 큐에 기록하고 다음 행으로 넘어간다. */
    public Summary runDue() {
        List<Claimed> claimed = claimDue();
        int done = 0;
        int skipped = 0;
        int retry = 0;
        int failed = 0;
        for (Claimed row : claimed) {
            switch (process(row)) {
                case DONE -> done++;
                case SKIPPED -> skipped++;
                case RETRY -> retry++;
                case FAILED -> failed++;
                case STALE -> { }
            }
        }
        Summary summary = new Summary(claimed.size(), done, skipped, retry, failed);
        if (summary.claimed() > 0) {
            log.info("apple-revocation claimed={} done={} skipped={} retry={} failed={}",
                summary.claimed(), done, skipped, retry, failed);
        }
        return summary;
    }

    private List<Claimed> claimDue() {
        return jdbc.query(
            """
            update apple_revocations
               set next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
             where id in (
                   select id from apple_revocations
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by next_attempt_at
                    limit ?
                    for update skip locked)
            returning id, user_id, attempts
            """,
            (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getInt("attempts")),
            (double) properties.appleLease().toSeconds(), properties.appleBatchSize());
    }

    private Outcome process(Claimed row) {
        AppleTokenRevoker.Result result;
        try {
            result = revoker.revoke(row.userId());
        } catch (RuntimeException e) {
            return recordFailure(row, e);
        }
        String status = result == AppleTokenRevoker.Result.DONE ? "DONE" : "SKIPPED";
        // attempts가 집을 때와 같을 때만 반영한다: 임대가 만료돼 다른 실행이 먼저 처리했다면 0행이고 STALE이다.
        int updated = jdbc.update(
            "update apple_revocations set status = ?, updated_at = now() "
                + "where id = ? and status = 'PENDING' and attempts = ?",
            status, row.id(), row.attempts());
        if (updated == 0) {
            return Outcome.STALE;
        }
        return result == AppleTokenRevoker.Result.DONE ? Outcome.DONE : Outcome.SKIPPED;
    }

    /** 실패: 횟수를 올리고 상한이면 FAILED, 아니면 백오프 뒤로 미룬다. 오류에는 예외 종류만 남기고 개인정보는 쓰지 않는다. */
    private Outcome recordFailure(Claimed row, RuntimeException e) {
        int attempts = row.attempts() + 1;
        String error = e.getClass().getSimpleName();
        if (error.length() > ERROR_MAX) {
            error = error.substring(0, ERROR_MAX);
        }
        // 집을 때의 attempts와 같을 때만 반영한다: 임대가 만료돼 다른 실행이 먼저 처리했다면 횟수를 되감지 않고 STALE로 끝낸다.
        if (attempts >= properties.appleMaxAttempts()) {
            int updated = jdbc.update(
                "update apple_revocations set status = 'FAILED', attempts = ?, last_error = ?, updated_at = now() "
                    + "where id = ? and status = 'PENDING' and attempts = ?",
                attempts, error, row.id(), row.attempts());
            if (updated == 0) {
                return Outcome.STALE;
            }
            log.warn("apple-revocation 최대 재시도 도달, FAILED 처리 attempts={}", attempts);
            return Outcome.FAILED;
        }
        Duration delay = AppleRevocationBackoff.delay(
            attempts, properties.appleBaseDelay(), properties.appleMaxDelay(), properties.appleJitter(), random);
        int updated = jdbc.update(
            "update apple_revocations set attempts = ?, last_error = ?, "
                + "next_attempt_at = now() + make_interval(secs => ?), updated_at = now() "
                + "where id = ? and status = 'PENDING' and attempts = ?",
            attempts, error, delay.toMillis() / 1000.0, row.id(), row.attempts());
        return updated == 0 ? Outcome.STALE : Outcome.RETRY;
    }

    private record Claimed(UUID id, UUID userId, int attempts) {
    }

    /** STALE: 임대가 만료돼 다른 실행이 먼저 이 행의 결과를 반영한 경우. 집계하지 않는다. */
    private enum Outcome { DONE, SKIPPED, RETRY, FAILED, STALE }

    public record Summary(int claimed, int done, int skipped, int retry, int failed) {
    }
}
