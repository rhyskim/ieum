package com.ieum.backend.domain.question.job;

import com.ieum.backend.domain.question.service.ReminderService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 15:00~16:55(Asia/Seoul) 5분마다 리마인드 보정. 중복 발송은 reminder_sent 조건부 UPDATE가 막는다. 테스트에서는 끈다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.question.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderJob {

    private final ReminderService service;

    @Scheduled(cron = "0 0/5 15-16 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "question-reminder", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void run() {
        service.remindDue();
    }
}
