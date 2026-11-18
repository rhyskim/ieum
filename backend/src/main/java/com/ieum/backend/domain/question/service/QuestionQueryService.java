package com.ieum.backend.domain.question.service;

import com.ieum.backend.domain.question.dto.QuestionDtos.TodayQuestionResponse;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.question.entity.Question;
import com.ieum.backend.domain.question.repository.DailyQuestionRepository;
import com.ieum.backend.global.common.TimeZoneConstants;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionQueryService {

    private final DailyQuestionRepository dailyQuestionRepository;
    private final Clock clock;

    /** 현재 사이클(KST 19:00 기준)의 질문. 접근 권한은 컨트롤러의 @GroupMemberOnly가 검사한다. 아직 배정 전이면 404. */
    @Transactional(readOnly = true)
    public TodayQuestionResponse today(UUID groupId) {
        DailyQuestion dq = dailyQuestionRepository.findByGroup_IdAndCycleDate(groupId, currentCycleDate())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Question q = dq.getQuestion();
        return new TodayQuestionResponse(dq.getId(), groupId, dq.getCycleDate(), q.getQuestionText(), q.getCategory(),
            q.getTtsGuideText(), q.getFollowUpHint(), dq.getRevealStatus(), dq.getAutoRevealAt(), false);
    }

    /** 19:00 이전은 전날 사이클이다. */
    private LocalDate currentCycleDate() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TimeZoneConstants.KST));
        return now.toLocalTime().isBefore(TimeZoneConstants.DAILY_QUESTION_ASSIGN_TIME_KST)
            ? now.toLocalDate().minusDays(1)
            : now.toLocalDate();
    }
}
