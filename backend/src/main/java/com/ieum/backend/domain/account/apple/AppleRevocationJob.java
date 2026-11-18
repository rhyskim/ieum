package com.ieum.backend.domain.account.apple;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Apple 해지 큐를 5분마다 처리한다. 서버가 여러 대여도 ShedLock으로 한 곳에서만 돈다. 테스트에서는 끈다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.account.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AppleRevocationJob {

    private final AppleRevocationService service;

    @Scheduled(fixedDelayString = "${ieum.account.apple-revocation-interval:PT5M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "apple-revocation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void run() {
        service.runDue();
    }
}
