package com.ieum.backend.domain.question.service;

import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.global.common.TimeZoneConstants;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 보정형 배정(ADR-0006): 현재 사이클에 질문이 없는 ACTIVE 그룹만 골라 채운다. 몇 번을 실행해도, 서버가 몇 대여도 결과가 같다.
 * 사이클은 KST 19:00~다음날 19:00이고, 이 작업은 19:00 이후의 그날 사이클만 다룬다(지난 사이클은 보정하지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionAssignmentService {

    private final DailyQuestionRepository dailyQuestionRepository;
    private final GroupQuestionAssigner assigner;
    private final Clock clock;

    /** @return 이번 실행이 새로 배정한 그룹 수. 19시 전이면 0. */
    public int assignCurrentCycle() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TimeZoneConstants.KST));
        if (now.toLocalTime().isBefore(TimeZoneConstants.DAILY_QUESTION_ASSIGN_TIME_KST)) {
            return 0;
        }
        LocalDate cycleDate = now.toLocalDate();

        List<UUID> groupIds = dailyQuestionRepository.findActiveGroupIdsWithoutQuestion(cycleDate);
        int created = 0;
        for (UUID groupId : groupIds) {
            try {
                if (assigner.assign(groupId, cycleDate) == GroupQuestionAssigner.Result.CREATED) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("질문 배정 실패(다른 그룹은 계속): groupId={}, cycleDate={}", groupId, cycleDate, e);
            }
        }
        if (!groupIds.isEmpty()) {
            log.info("질문 배정: cycleDate={}, 대상 {}개 그룹, 신규 {}개", cycleDate, groupIds.size(), created);
        }
        return created;
    }
}
