package com.ieum.backend.domain.answer.dto;

import com.ieum.backend.domain.common.enums.QuestionCategory;
import com.ieum.backend.domain.common.enums.RevealStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnswerDtos {

    private AnswerDtos() {}

    /** 최소 글자 수(공백 제외 30자)는 서비스가 검사한다: 위반 시 ANSWER_TOO_SHORT. */
    public record SaveAnswerRequest(@NotNull(message = "답변을 입력해 주세요.") String textContent) {}

    public record AnswerResponse(
        UUID answerId,
        UUID dailyQuestionId,
        String textContent,
        RevealStatus revealStatus,
        Instant updatedAt
    ) {}

    /** 멤버별 답변 여부와 호칭만. 본문은 절대 담지 않는다(서버 마스킹). */
    public record MemberAnswerStatus(UUID userId, String displayName, boolean hasAnswered, boolean mine) {}

    public record QuestionDetailResponse(
        UUID dailyQuestionId,
        UUID groupId,
        LocalDate cycleDate,
        String questionText,
        QuestionCategory category,
        String ttsGuideText,
        String followUpHint,
        RevealStatus revealStatus,
        Instant autoRevealAt,
        Instant revealedAt,
        boolean hasAnswered,
        List<MemberAnswerStatus> members
    ) {}

    public record AnswerView(
        UUID answerId,
        UUID authorUserId,
        String authorName,
        boolean mine,
        String textContent,
        Instant updatedAt
    ) {}

    /** 열람 권한이 없는 타인의 답변은 목록에 아예 없다(null 자리표시도 없다). */
    public record AnswerListResponse(RevealStatus revealStatus, boolean hasAnswered, List<AnswerView> answers) {}
}
