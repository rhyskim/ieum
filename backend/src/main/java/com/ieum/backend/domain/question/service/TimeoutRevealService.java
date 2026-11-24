package com.ieum.backend.domain.question.service;

import com.ieum.backend.domain.answer.service.RevealEvaluator;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17시 시간 초과 공개 작업(ADR-0007 결정 2). 조회 시점 지연 전환과 같은 {@link RevealEvaluator#revealIfTimedOut}을 쓰므로
 * 어느 쪽이 먼저 전환해도 푸시는 전환한 쪽에서 한 번만 나간다. 후보는 id 오름차순이고 질문마다 별도 트랜잭션이다.
 * 답변이 0건인 질문은 후보가 아니므로 LOCKED로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutRevealService {

    private final DailyQuestionRepository dailyQuestionRepository;
    private final RevealEvaluator revealEvaluator;
    private final TransactionTemplate tx;
    private final Clock clock;

    public void revealDue() {
        Instant now = clock.instant();
        for (UUID dqId : dailyQuestionRepository.findTimeoutRevealCandidateIds(now)) {
            try {
                tx.executeWithoutResult(s -> revealEvaluator.revealIfTimedOut(dqId, now));
            } catch (RuntimeException e) {
                log.warn("시간 초과 공개 처리 실패(다음 질문으로 계속): dqId={}", dqId, e);
            }
        }
    }
}
