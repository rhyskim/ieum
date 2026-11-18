package com.ieum.backend.domain.question.service;

import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.notification.event.GroupPushEvent;
import com.ieum.backend.domain.notification.event.GroupPushEvent.Type;
import com.ieum.backend.domain.question.config.QuestionProperties;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.domain.question.repository.QuestionRepository;
import com.ieum.backend.global.common.TimeZoneConstants;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그룹 1개의 배정을 자기 트랜잭션 하나로 처리한다. 그룹마다 트랜잭션이 갈려 한 그룹의 실패가 다른 그룹에 번지지 않는다.
 * 푸시 이벤트는 새 행이 실제로 생겼을 때만 발행하고, 발송은 커밋 후 비동기다(ADR-0004·0006).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GroupQuestionAssigner {

    enum Result { CREATED, ALREADY_ASSIGNED, BANK_EXHAUSTED }

    private final DailyQuestionRepository dailyQuestionRepository;
    private final QuestionRepository questionRepository;
    private final GroupMemberRepository memberRepository;
    private final ApplicationEventPublisher publisher;
    private final QuestionProperties properties;

    @Transactional
    public Result assign(UUID groupId, LocalDate cycleDate) {
        int inserted = dailyQuestionRepository.assignNext(
            groupId, cycleDate,
            TimeZoneConstants.assignedAt(cycleDate),
            TimeZoneConstants.autoRevealAt(cycleDate));

        if (inserted == 0) {
            if (dailyQuestionRepository.existsByGroup_IdAndCycleDate(groupId, cycleDate)) {
                return Result.ALREADY_ASSIGNED;
            }
            log.error("질문 은행이 소진되어 배정을 건너뜁니다: groupId={}, cycleDate={}", groupId, cycleDate);
            return Result.BANK_EXHAUSTED;
        }

        warnIfLowStock(groupId);
        for (GroupMember member : memberRepository.findValidActive(groupId)) {
            publisher.publishEvent(new GroupPushEvent(Type.QUESTION_ASSIGNED, groupId, member.getUser().getId()));
        }
        return Result.CREATED;
    }

    private void warnIfLowStock(UUID groupId) {
        long remaining = questionRepository.countByActiveTrue() - dailyQuestionRepository.countByGroup_Id(groupId);
        if (remaining <= properties.lowStockThreshold()) {
            log.warn("남은 질문이 {}개입니다(임계치 {}): groupId={}", remaining, properties.lowStockThreshold(), groupId);
        }
    }
}
