package com.ieum.backend.domain.question.job;

import com.ieum.backend.domain.question.service.TimeoutRevealService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 17:00~17:55(Asia/Seoul) 5분마다 시간 초과 공개 보정. 전환·푸시 1회 보장은 조건부 UPDATE가 맡는다. 테스트에서는 끈다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.question.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TimeoutRevealJob {

    private final TimeoutRevealService service;

    @Scheduled(cron = "0 0/5 17 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "timeout-reveal", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void run() {
        service.revealDue();
    }
}
