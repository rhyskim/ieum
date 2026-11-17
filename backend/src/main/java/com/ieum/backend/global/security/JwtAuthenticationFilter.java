package com.ieum.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.exception.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            CurrentUser currentUser = jwtProvider.parseAccessToken(header.substring(BEARER_PREFIX.length()));
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, Collections.emptyList())
            );
        } catch (ExpiredJwtException e) {
            // 공개 경로는 기기에 남은 만료 Access가 붙어 와도 막지 않는다. 보호 경로만 TOKEN_EXPIRED로 갱신을 유도한다.
            if (!PublicEndpoints.matches(request)) {
                writeError(response, ErrorCode.TOKEN_EXPIRED);
                return;
            }
            SecurityContextHolder.clearContext();
        } catch (JwtException | IllegalArgumentException e) {
            // 토큰 원문은 로그에 남기지 않는다. 인증 정보 없이 진행하면 EntryPoint가 401 UNAUTHORIZED를 응답한다.
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }
}
