package com.ieum.backend.domain.auth.social;

import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Apple identity token(JWT)을 Apple 공개키(JWKS)로 서명 검증하고 iss·aud·exp를 확인한다. */
@Component
public class AppleTokenVerifier implements SocialTokenVerifier {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String JWKS_URI = "https://appleid.apple.com/auth/keys";

    private final JwtDecoder decoder;

    public AppleTokenVerifier(SocialAuthProperties properties) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
        String clientId = properties.appleClientId();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
            "aud", aud -> clientId != null && !clientId.isBlank() && aud != null && aud.contains(clientId));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        this.decoder = nimbus;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.APPLE;
    }

    @Override
    public SocialProfile verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return new SocialProfile(jwt.getSubject(), jwt.getClaimAsString("email"), null);
        } catch (BadJwtException e) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        } catch (JwtException e) {
            // 공개키 조회 실패 등 제공자 측 문제
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }
}
