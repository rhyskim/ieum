package com.ieum.backend.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;

/**
 * 인증 없이 호출할 수 있는 경로의 단일 정의.
 * SecurityConfig의 permitAll과 JwtAuthenticationFilter(만료 토큰 무시 판단)가 같은 목록을 쓴다.
 */
public final class PublicEndpoints {

    public static final String[] PATTERNS = {
        "/api/v1/health",
        "/api/v1/policies",
        "/api/v1/auth/refresh",
        "/api/v1/auth/kakao",
        "/api/v1/auth/naver",
        "/api/v1/auth/apple",
        "/api/v1/auth/*/restore",
        "/actuator/health",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicEndpoints() {
    }

    public static boolean matches(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : PATTERNS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
