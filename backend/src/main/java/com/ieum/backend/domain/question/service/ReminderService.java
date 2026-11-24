package com.ieum.backend.domain.question.service;

import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.notification.event.GroupPushEvent;
import com.ieum.backend.domain.notification.event.GroupPushEvent.Type;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 마감 2시간 전 리마인드(ADR-0004·0006·0007). 보정형이라 서버가 꺼져 있다 켜져도 다음 실행이 보낸다.
 * 발송권은 {@code reminder_sent} 조건부 UPDATE 1행으로만 얻으므로 서버가 여러 대여도 중복되지 않는다.
 * 질문마다 별도 트랜잭션이라 한 번에 잡는 락은 dq 행 하나뿐이다. 푸시는 커밋 후 비동기로 나간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final Duration WINDOW = Duration.ofHours(2);

    private final DailyQuestionRepository dailyQuestionRepository;
    private final AnswerRepository answerRepository;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 이번 실행이 발송권을 얻은 질문 수를 돌려준다. */
    public int remindDue() {
        Instant now = clock.instant();
        Instant windowEnd = now.plus(WINDOW);
        int sent = 0;
        for (UUID dqId : dailyQuestionRepository.findReminderCandidateIds(now, windowEnd)) {
            try {
                Boolean won = tx.execute(s -> remind(dqId, now, windowEnd));
                if (Boolean.TRUE.equals(won)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("리마인드 처리 실패(다음 질문으로 계속): dqId={}", dqId, e);
            }
        }
        return sent;
    }

    private boolean remind(UUID dqId, Instant now, Instant windowEnd) {
        if (dailyQuestionRepository.markReminderSent(dqId, now, windowEnd) != 1) {
            return false;
        }
        UUID groupId = dailyQuestionRepository.findGroupId(dqId).orElseThrow();
        List<UUID> recipients = answerRepository.findUnansweredValidUserIds(dqId, groupId);
        recipients.forEach(userId -> publisher.publishEvent(new GroupPushEvent(Type.REMINDER, groupId, userId)));
        return true;
    }
}
