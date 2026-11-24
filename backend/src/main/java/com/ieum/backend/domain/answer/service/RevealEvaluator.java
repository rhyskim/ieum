package com.ieum.backend.domain.answer.service;

import com.ieum.backend.domain.answer.repository.AnswerRepository;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.notification.event.GroupPushEvent;
import com.ieum.backend.domain.notification.event.GroupPushEvent.Type;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 상태 전환의 유일한 진입점(ADR-0007). 전환은 항상 {@code WHERE reveal_status='LOCKED'} 조건부 UPDATE이고,
 * 1행이 바뀐 호출만 알림을 낸다. 전원 답변이 시간 초과보다 우선한다. REVEALED_*는 되돌리거나 승격하지 않는다.
 * <p>
 * 호출자는 {@link #evaluateLocked}를 쓰기 전에 반드시 daily_questions 행을 FOR UPDATE로 잠가야 한다:
 * 잠그지 않으면 두 트랜잭션이 서로의 미커밋 답변을 못 봐 둘 다 "한 명 남았다"고 판단하는 write skew가 난다.
 */
@Component
@RequiredArgsConstructor
public class RevealEvaluator {

    private final DailyQuestionRepository dailyQuestionRepository;
    private final AnswerRepository answerRepository;
    private final GroupMemberRepository memberRepository;
    private final ApplicationEventPublisher publisher;
    private final TimeoutRevealNotifier timeoutNotifier;

    /** 저장·멤버 변동 후 재평가. 전원 답변 → REVEALED_ALL, 아니면 시간 초과 → REVEALED_TIMEOUT. 최종 상태를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RevealStatus evaluateLocked(UUID dqId, UUID groupId, Instant now) {
        RevealStatus status = dailyQuestionRepository.findRevealStatus(dqId).orElseThrow();
        if (status != RevealStatus.LOCKED) {
            return status;
        }
        boolean allAnswered = memberRepository.countValidActive(groupId) > 0
            && answerRepository.countUnansweredValidMembers(dqId, groupId) == 0;
        if (allAnswered) {
            if (dailyQuestionRepository.revealAllIfLocked(dqId, now) == 1) {
                notifyAllAnswered(groupId);
            }
        } else {
            revealTimeoutAndNotify(dqId, groupId, now);
        }
        return dailyQuestionRepository.findRevealStatus(dqId).orElseThrow();
    }

    /**
     * 시간 초과 전환의 유일한 진입점: 조회 시점의 지연 전환(혼합형)과 17시 작업이 모두 이 메서드를 쓴다.
     * 조건부 UPDATE 한 번이라 추가 잠금이 없고, 1행을 바꾼 호출만 공개 푸시를 발행하므로 어느 쪽이 먼저 전환해도
     * 푸시는 정확히 한 번 나간다. 전환이 없는 평소 조회에서는 추가 쿼리가 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RevealStatus revealIfTimedOut(UUID dqId, Instant now) {
        if (dailyQuestionRepository.revealTimeoutIfDue(dqId, now) == 1) {
            timeoutNotifier.notifyRevealed(dqId, dailyQuestionRepository.findGroupId(dqId).orElseThrow());
        }
        return dailyQuestionRepository.findRevealStatus(dqId).orElseThrow();
    }

    private void revealTimeoutAndNotify(UUID dqId, UUID groupId, Instant now) {
        if (dailyQuestionRepository.revealTimeoutIfDue(dqId, now) == 1) {
            timeoutNotifier.notifyRevealed(dqId, groupId);
        }
    }

    /** 개인정보 없는 고정 문구는 리스너가 만든다. 커밋 후 비동기로 발송된다(ADR-0004). */
    private void notifyAllAnswered(UUID groupId) {
        for (GroupMember member : memberRepository.findValidActive(groupId)) {
            publisher.publishEvent(new GroupPushEvent(Type.ALL_ANSWERED, groupId, member.getUser().getId()));
        }
    }
}
