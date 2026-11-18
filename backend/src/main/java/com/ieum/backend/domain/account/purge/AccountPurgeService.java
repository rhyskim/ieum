package com.ieum.backend.domain.account.purge;

import com.ieum.backend.domain.account.config.AccountProperties;
import com.ieum.backend.domain.account.storage.AudioObjectStorage;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 탈퇴 후 31일이 지난 계정의 개인정보 파기(ADR-0002 결정 1·9·10). 사용자 1명 단위로 처리하고 한 명의 실패가 다른 사용자를 막지 않는다.
 * 클래스 레벨 트랜잭션을 두지 않는다: S3 호출 중에 DB 커넥션·락을 잡지 않고, DB 단계만 {@link AccountPurgeTransaction}의 한 트랜잭션으로 묶는다.
 *
 * <p>순서: ① 조건부 UPDATE로 임대(클레임) → ② 외부 자원(S3) 삭제 → ③ DB 한 트랜잭션(익명화·완료 표시). 외부 자원이 먼저인 이유는
 * DB를 먼저 지우면 어떤 사용자가 어떤 객체 키를 가졌는지 잃어 고아 객체가 남기 때문이다. 모든 단계가 멱등이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountPurgeService {

    private final JdbcTemplate jdbc;
    private final AccountProperties properties;
    private final AudioObjectStorage audioStorage;
    private final AccountPurgeTransaction purgeTransaction;

    /** 대상을 모아 한 명씩 처리하고 결과를 로그 한 줄(개인정보 없음)로 남긴다. */
    public Summary runBatch() {
        List<UUID> candidates = jdbc.queryForList(
            """
            select id from users
             where deleted_at is not null
               and purged_at is null
               and deleted_at <= now() - make_interval(days => ?)
               and (purge_started_at is null or purge_started_at < now() - make_interval(secs => ?))
             order by deleted_at
             limit ?
            """,
            UUID.class,
            properties.purgeAfterDays(), (double) properties.purgeLease().toSeconds(), properties.purgeBatchSize());

        int purged = 0;
        int failed = 0;
        int skipped = 0;
        for (UUID userId : candidates) {
            switch (purgeOne(userId)) {
                case PURGED -> purged++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        Summary summary = new Summary(candidates.size(), purged, failed, skipped);
        log.info("account-purge candidates={} purged={} failed={} skipped={}", summary.candidates(), purged, failed, skipped);
        return summary;
    }

    /** 사용자 1명 파기. 이미 처리됐거나 다른 실행이 임대 중이면 건너뛴다. 실패하면 purged_at이 비어 있어 다음 실행이 이어받는다. */
    public Outcome purgeOne(UUID userId) {
        if (!claim(userId)) {
            return Outcome.SKIPPED;
        }
        try {
            // 콘텐츠 선택이 없거나(과거 탈퇴 데이터) KEEP이면 아무것도 지우지 않는다. DELETE만 삭제한다.
            boolean deleteContent = "DELETE".equals(
                jdbc.queryForObject("select withdraw_content_choice from users where id = ?", String.class, userId));
            if (deleteContent) {
                deleteAnswerAudio(userId);
            }
            // 임대 만료 경계에서 다른 실행이 먼저 끝냈다면 이 실행은 건너뜀으로 센다(이중 집계 방지).
            return purgeTransaction.finalizeUser(userId, deleteContent) ? Outcome.PURGED : Outcome.SKIPPED;
        } catch (RuntimeException e) {
            // 예외 종류만 남긴다(이름·이메일 등 개인정보를 로그에 쓰지 않는다). 다음 실행이 처음부터 다시 한다.
            log.warn("account-purge 실패 userId={} reason={}", userId, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /**
     * 조건부 UPDATE로 임대한다(DB 시각). 복구 마감 30일/파기 시작 31일이라 복구와 겹치지 않으며, 이 UPDATE는 서버 간 중복 실행과
     * 시계 차이에 대한 이중 방어다. purge_started_at은 "마지막 시도 시각"이라 임대 시간이 지나면 다른 실행이 이어받는다.
     */
    private boolean claim(UUID userId) {
        return jdbc.update(
            """
            update users set purge_started_at = now()
             where id = ?
               and deleted_at is not null
               and purged_at is null
               and deleted_at <= now() - make_interval(days => ?)
               and (purge_started_at is null or purge_started_at < now() - make_interval(secs => ?))
            """,
            userId, properties.purgeAfterDays(), (double) properties.purgeLease().toSeconds()) == 1;
    }

    /** 본인이 쓴 답변의 음성을 S3에서 지운다. 하나라도 실패하면 예외로 중단되고 DB 행은 그대로 남는다. */
    private void deleteAnswerAudio(UUID userId) {
        List<String> keys = jdbc.queryForList(
            "select audio_object_key from answers where user_id = ? and audio_object_key is not null",
            String.class, userId);
        for (String key : keys) {
            audioStorage.delete(key);
        }
    }

    public enum Outcome { PURGED, FAILED, SKIPPED }

    public record Summary(int candidates, int purged, int failed, int skipped) {
    }
}
