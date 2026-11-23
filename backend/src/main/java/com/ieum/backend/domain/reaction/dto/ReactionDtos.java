package com.ieum.backend.domain.reaction.dto;

import com.ieum.backend.domain.common.enums.EmojiCode;
import java.util.List;
import java.util.UUID;

public final class ReactionDtos {

    private ReactionDtos() {}

    /** 한 답변의 이모지 한 종류 집계. 5종 모두 항상 내려가므로 앱은 없는 종류를 따로 채우지 않는다. */
    public record EmojiReaction(EmojiCode emojiCode, int count, boolean mine) {}

    public record ReactionSummary(UUID answerId, List<EmojiReaction> reactions) {}

    public record Reactor(UUID userId, String displayName, boolean mine) {}

    public record EmojiReactors(EmojiCode emojiCode, int count, boolean mine, List<Reactor> reactors) {}

    public record ReactionListResponse(UUID answerId, List<EmojiReactors> reactions) {}
}
