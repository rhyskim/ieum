package com.ieum.backend.global.security;

import com.ieum.backend.domain.common.enums.MembershipTier;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    private static final String TIER_CLAIM = "tier";

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock = Clock.systemUTC();

    public JwtProvider(JwtProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ieum.auth.jwt-secret(JWT_SECRET)은 32바이트 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
    }

    public String createAccessToken(UUID userId, MembershipTier tier) {
        Date now = Date.from(clock.instant());
        Date expiry = Date.from(clock.instant().plus(properties.accessTtl()));
        return Jwts.builder()
            .subject(userId.toString())
            .claim(TIER_CLAIM, tier.name())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact();
    }

    /** 만료는 {@link ExpiredJwtException}, 그 외 위조·형식 오류는 {@link JwtException}으로 전달한다. */
    public CurrentUser parseAccessToken(String token) {
        var claims = Jwts.parser()
            .verifyWith(key)
            .clock(() -> Date.from(clock.instant()))
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return new CurrentUser(
            UUID.fromString(claims.getSubject()),
            MembershipTier.valueOf(claims.get(TIER_CLAIM, String.class))
        );
    }
}
