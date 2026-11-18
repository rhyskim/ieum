package com.ieum.backend.domain.question.job;

import com.ieum.backend.domain.question.service.QuestionAssignmentService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 19:00~23:55(Asia/Seoul) 5분마다 보정 배정. ShedLock은 서버 간 불필요한 중복 실행을 줄이고, 락이 만료돼 겹쳐도
 * UNIQUE(group_id, cycle_date)가 중복을 막는다. 테스트에서는 끈다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.question.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class QuestionAssignmentJob {

    private final QuestionAssignmentService service;

    @Scheduled(cron = "0 0/5 19-23 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "question-assignment", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void run() {
        service.assignCurrentCycle();
    }
}
