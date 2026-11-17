package com.ieum.backend.domain.auth.dto;

import com.ieum.backend.domain.common.enums.OAuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** name은 Apple이 첫 로그인 때 앱에만 주는 이름이다(identity token에는 없음). */
    public record SocialLoginRequest(
        @NotBlank String token,
        @Size(max = 50) String name,
        @Size(max = 100) String deviceLabel
    ) {
    }

    public record RefreshRequest(
        @NotBlank String refreshToken,
        @Size(max = 100) String deviceLabel
    ) {
    }

    public record LogoutRequest(@NotBlank String refreshToken, @Size(max = 512) String fcmToken) {
    }

    public record LoginResponse(
        String accessToken,
        String refreshToken,
        long accessExpiresInSeconds,
        boolean newUser
    ) {
    }

    public record IdentityResponse(OAuthProvider provider, Instant linkedAt) {
    }

    /** id는 기기 세션(refresh family) 식별자다. Rotation으로 토큰이 바뀌어도 변하지 않는다. */
    public record SessionResponse(
        UUID id,
        String deviceLabel,
        Instant createdAt,
        Instant lastUsedAt
    ) {
    }
}
