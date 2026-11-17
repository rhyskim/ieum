package com.ieum.backend.domain.auth.controller;

import com.ieum.backend.domain.auth.service.AuthService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final AuthService authService;

    /** 탈퇴 요청: 소프트 삭제 후 30일 유예. 모든 기기 세션이 폐기된다. */
    @DeleteMapping
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal CurrentUser currentUser) {
        authService.withdraw(currentUser.userId());
        return ApiResponse.ok(null);
    }
}
