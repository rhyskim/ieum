package com.ieum.backend.domain.archive.controller;

import com.ieum.backend.domain.archive.dto.ArchiveDtos.ArchiveItem;
import com.ieum.backend.domain.archive.dto.ArchiveDtos.ArchivePage;
import com.ieum.backend.domain.archive.service.ArchiveService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import com.ieum.backend.global.security.GroupMemberOnly;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/archive")
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveService archiveService;

    /** 지난 질문 타임라인(cycle_date 내림차순). 다음 페이지는 응답의 nextCursor를 그대로 넘긴다. */
    @GetMapping
    @GroupMemberOnly
    public ApiResponse<ArchivePage> timeline(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer size
    ) {
        return ApiResponse.ok(archiveService.timeline(groupId, user.userId(), cursor, size));
    }

    /** 한 질문의 답변과 리액션 집계(타임라인 항목과 같은 모양). */
    @GetMapping("/{dailyQuestionId}")
    @GroupMemberOnly
    public ApiResponse<ArchiveItem> item(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID dailyQuestionId
    ) {
        return ApiResponse.ok(archiveService.item(groupId, dailyQuestionId, user.userId()));
    }
}
