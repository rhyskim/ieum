package com.ieum.backend.domain.auth.controller;

import com.ieum.backend.domain.auth.dto.AuthDtos.WithdrawRequest;
import com.ieum.backend.domain.auth.service.AccountService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final AccountService accountService;

    /**
     * 탈퇴 요청: 소프트 삭제 후 30일 유예. 콘텐츠 선택(KEEP 계속 공개 / DELETE 영구 삭제)은 필수이며
     * 실제 처리는 파기 시점에 한다. 모든 기기 세션과 기기 토큰이 정리되고 방장이면 즉시 위임된다.
     */
    @DeleteMapping
    public ApiResponse<Void> withdraw(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody WithdrawRequest request
    ) {
        accountService.withdraw(currentUser.userId(), request.contentChoice());
        return ApiResponse.ok(null);
    }
}
