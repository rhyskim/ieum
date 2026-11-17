package com.ieum.backend.domain.auth.controller;

import com.ieum.backend.domain.auth.dto.AuthDtos.LoginResponse;
import com.ieum.backend.domain.auth.dto.AuthDtos.LogoutRequest;
import com.ieum.backend.domain.auth.dto.AuthDtos.RefreshRequest;
import com.ieum.backend.domain.auth.dto.AuthDtos.SessionResponse;
import com.ieum.backend.domain.auth.dto.AuthDtos.SocialLoginRequest;
import com.ieum.backend.domain.auth.service.AuthService;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import com.ieum.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/{provider}")
    public ApiResponse<LoginResponse> socialLogin(
        @PathVariable String provider,
        @Valid @RequestBody SocialLoginRequest request
    ) {
        return ApiResponse.ok(authService.login(parseProvider(provider), request));
    }

    @PostMapping("/{provider}/restore")
    public ApiResponse<LoginResponse> restore(
        @PathVariable String provider,
        @Valid @RequestBody SocialLoginRequest request
    ) {
        return ApiResponse.ok(authService.restore(parseProvider(provider), request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken(), request.deviceLabel()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody LogoutRequest request
    ) {
        authService.logout(currentUser.userId(), request.refreshToken());
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionResponse>> sessions(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(authService.listSessions(currentUser.userId()));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> revokeSession(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        authService.revokeSession(currentUser.userId(), id);
        return ApiResponse.ok(null);
    }

    private static OAuthProvider parseProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
