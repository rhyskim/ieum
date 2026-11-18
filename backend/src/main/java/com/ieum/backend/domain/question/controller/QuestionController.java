package com.ieum.backend.domain.question.controller;

import com.ieum.backend.domain.question.dto.QuestionDtos.TodayQuestionResponse;
import com.ieum.backend.domain.question.service.QuestionQueryService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.GroupMemberOnly;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionQueryService queryService;

    @GetMapping("/today")
    @GroupMemberOnly
    public ApiResponse<TodayQuestionResponse> today(@PathVariable UUID groupId) {
        return ApiResponse.ok(queryService.today(groupId));
    }
}
