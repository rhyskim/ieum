package com.ieum.backend.domain.archive.dto;

import com.ieum.backend.domain.common.enums.QuestionCategory;
import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.EmojiReaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ArchiveDtos {

    private ArchiveDtos() {}

    /** 열람 권한이 있는 답변만 담긴다(서버 마스킹). 리액션 집계는 5종 모두. */
    public record ArchiveAnswer(
        UUID answerId,
        UUID authorUserId,
        String authorName,
        boolean mine,
        String textContent,
        Instant updatedAt,
        List<EmojiReaction> reactions
    ) {}

    public record ArchiveItem(
        UUID dailyQuestionId,
        LocalDate cycleDate,
        String questionText,
        QuestionCategory category,
        RevealStatus revealStatus,
        boolean hasAnswered,
        List<ArchiveAnswer> answers
    ) {}

    /** nextCursor가 null이면 마지막 페이지다. */
    public record ArchivePage(List<ArchiveItem> items, String nextCursor) {}
}
