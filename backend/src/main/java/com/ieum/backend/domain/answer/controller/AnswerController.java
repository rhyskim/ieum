package com.ieum.backend.domain.answer.controller;

import com.ieum.backend.domain.answer.dto.AnswerDtos.AnswerListResponse;
import com.ieum.backend.domain.answer.dto.AnswerDtos.AnswerResponse;
import com.ieum.backend.domain.answer.dto.AnswerDtos.QuestionDetailResponse;
import com.ieum.backend.domain.answer.dto.AnswerDtos.SaveAnswerRequest;
import com.ieum.backend.domain.answer.service.AnswerQueryService;
import com.ieum.backend.domain.answer.service.AnswerService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import com.ieum.backend.global.security.GroupMemberOnly;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/questions/{dailyQuestionId}")
@RequiredArgsConstructor
public class AnswerController {

    private final AnswerService answerService;
    private final AnswerQueryService queryService;

    /** 내 답변 저장·수정(멱등 PUT). 질문당 한 사용자 한 건. */
    @PutMapping("/answers/me")
    @GroupMemberOnly
    public ApiResponse<AnswerResponse> save(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID dailyQuestionId,
        @Valid @RequestBody SaveAnswerRequest request
    ) {
        return ApiResponse.ok(answerService.save(groupId, dailyQuestionId, user.userId(), request.textContent()));
    }

    @GetMapping
    @GroupMemberOnly
    public ApiResponse<QuestionDetailResponse> detail(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID dailyQuestionId
    ) {
        return ApiResponse.ok(queryService.detail(groupId, dailyQuestionId, user.userId()));
    }

    @GetMapping("/answers")
    @GroupMemberOnly
    public ApiResponse<AnswerListResponse> answers(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID dailyQuestionId
    ) {
        return ApiResponse.ok(queryService.answers(groupId, dailyQuestionId, user.userId()));
    }
}
