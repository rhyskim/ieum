package com.ieum.backend.domain.question.dto;

import com.ieum.backend.domain.common.enums.QuestionCategory;
import com.ieum.backend.domain.common.enums.RevealStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class QuestionDtos {

    private QuestionDtos() {}

    /** hasAnswered는 요청한 본인의 답변 여부다. */
    public record TodayQuestionResponse(
        UUID dailyQuestionId,
        UUID groupId,
        LocalDate cycleDate,
        String questionText,
        QuestionCategory category,
        String ttsGuideText,
        String followUpHint,
        RevealStatus revealStatus,
        Instant autoRevealAt,
        boolean hasAnswered
    ) {}
}
