package com.ieum.backend.domain.account.purge;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 04:00(Asia/Seoul) 파기 배치. 서버가 여러 대여도 ShedLock으로 한 곳에서만 돌고, 락 시간을 넘겨 겹쳐도
 * 사용자 단위 임대(조건부 UPDATE)가 이중 처리를 막는다. 테스트에서는 끈다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.account.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AccountPurgeJob {

    private final AccountPurgeService service;

    @Scheduled(cron = "${ieum.account.purge-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "account-purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        service.runBatch();
    }
}
