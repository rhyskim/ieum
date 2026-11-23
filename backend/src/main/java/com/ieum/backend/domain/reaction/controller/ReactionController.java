package com.ieum.backend.domain.reaction.controller;

import com.ieum.backend.domain.common.enums.EmojiCode;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.ReactionListResponse;
import com.ieum.backend.domain.reaction.dto.ReactionDtos.ReactionSummary;
import com.ieum.backend.domain.reaction.service.ReactionService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import com.ieum.backend.global.security.GroupMemberOnly;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/answers/{answerId}/reactions")
@RequiredArgsConstructor
public class ReactionController {

    private final ReactionService reactionService;

    /** 이모지 달기(멱등). 이미 달려 있으면 그대로 성공한다. */
    @PutMapping("/{emojiCode}")
    @GroupMemberOnly
    public ApiResponse<ReactionSummary> add(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID answerId,
        @PathVariable EmojiCode emojiCode
    ) {
        return ApiResponse.ok(reactionService.add(groupId, answerId, user.userId(), emojiCode));
    }

    /** 이모지 취소(멱등). 없으면 그대로 성공한다. */
    @DeleteMapping("/{emojiCode}")
    @GroupMemberOnly
    public ApiResponse<ReactionSummary> remove(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID answerId,
        @PathVariable EmojiCode emojiCode
    ) {
        return ApiResponse.ok(reactionService.remove(groupId, answerId, user.userId(), emojiCode));
    }

    /** 종류별로 누가 달았는지. */
    @GetMapping
    @GroupMemberOnly
    public ApiResponse<ReactionListResponse> list(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID answerId
    ) {
        return ApiResponse.ok(reactionService.list(groupId, answerId, user.userId()));
    }
}
