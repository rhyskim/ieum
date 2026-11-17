package com.ieum.backend.domain.auth.service;

import com.ieum.backend.domain.auth.dto.AuthDtos.LoginResponse;
import com.ieum.backend.domain.auth.dto.AuthDtos.SessionResponse;
import com.ieum.backend.domain.auth.dto.AuthDtos.SocialLoginRequest;
import com.ieum.backend.domain.auth.entity.RefreshToken;
import com.ieum.backend.domain.auth.entity.UserIdentity;
import com.ieum.backend.domain.auth.repository.RefreshTokenRepository;
import com.ieum.backend.domain.auth.repository.UserIdentityRepository;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.auth.social.SocialTokenVerifier;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import com.ieum.backend.global.security.JwtProperties;
import com.ieum.backend.global.security.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Duration RESTORE_GRACE = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<SocialTokenVerifier> verifiers;
    private final UserIdentityRepository identityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final UserRegistrar userRegistrar;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final Clock clock = Clock.systemUTC();

    public AuthService(
        List<SocialTokenVerifier> socialTokenVerifiers,
        UserIdentityRepository identityRepository,
        RefreshTokenRepository refreshTokenRepository,
        UserRepository userRepository,
        UserRegistrar userRegistrar,
        JwtProvider jwtProvider,
        JwtProperties jwtProperties
    ) {
        this.verifiers = socialTokenVerifiers;
        this.identityRepository = identityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.userRegistrar = userRegistrar;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
    }

    /** 소셜 로그인. 클래스 레벨 트랜잭션을 두지 않는다: 가입 충돌 시 재조회가 새 트랜잭션에서 이뤄져야 한다. */
    public LoginResponse login(OAuthProvider provider, SocialLoginRequest request) {
        SocialProfile profile = verify(provider, request.token());

        Optional<UserIdentity> existing = identityRepository.findByProviderAndSubject(provider, profile.subject());
        User user;
        boolean newUser;
        if (existing.isPresent()) {
            user = existing.get().getUser();
            newUser = false;
        } else {
            try {
                user = userRegistrar.register(provider, profile, request.name());
                newUser = true;
            } catch (DataIntegrityViolationException e) {
                // 동시 가입: 다른 요청이 먼저 가입시켰다. 그 사용자로 로그인 처리한다.
                user = identityRepository.findByProviderAndSubject(provider, profile.subject())
                    .orElseThrow(() -> e)
                    .getUser();
                newUser = false;
            }
        }
        if (user.getDeletedAt() != null) {
            Instant restorableUntil = restorableUntil(user);
            if (!Instant.now(clock).isBefore(restorableUntil)) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTORE_EXPIRED);
            }
            throw new AccountPendingDeletionException(restorableUntil);
        }
        return issueTokens(user, UUID.randomUUID(), request.deviceLabel(), newUser);
    }

    /** 탈퇴 유예 중 계정 복구. 자동 복구가 아니라 소셜 토큰을 다시 검증한 요청에서만 수행한다. 외부 호출 중 DB 커넥션을 잡지 않도록 트랜잭션을 두지 않는다. */
    public LoginResponse restore(OAuthProvider provider, SocialLoginRequest request) {
        SocialProfile profile = verify(provider, request.token());
        User user = identityRepository.findByProviderAndSubject(provider, profile.subject())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
            .getUser();
        if (user.getDeletedAt() != null) {
            if (!Instant.now(clock).isBefore(restorableUntil(user))) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTORE_EXPIRED);
            }
            user.restore();
            userRepository.save(user);
        }
        return issueTokens(user, UUID.randomUUID(), request.deviceLabel(), false);
    }

    /** 탈퇴 요청: deleted_at 설정과 모든 기기 세션 폐기를 한 트랜잭션으로 처리한다. */
    @Transactional
    public void withdraw(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = Instant.now(clock);
        if (user.getDeletedAt() == null) {
            user.markDeleted(now);
        }
        refreshTokenRepository.revokeAllByUser(userId, now);
    }

    /**
     * Refresh Rotation. 사용 처리는 조건부 UPDATE(used_at IS NULL)로 원자적으로 하고,
     * 이미 사용된 토큰은 유예 시간 이내면 같은 family에 추가 발급, 초과면 family 폐기 + 401.
     * 401을 던져도 family 폐기가 롤백되지 않도록 noRollbackFor를 지정한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse refresh(String rawRefreshToken, String deviceLabel) {
        Instant now = Instant.now(clock);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (token.getRevokedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (refreshTokenRepository.markUsed(token.getId(), now) == 0) {
            // 이미 사용된 토큰. 동시 요청이 방금 사용했는지(유예 이내) 탈취 후 재사용인지 가른다.
            RefreshToken used = refreshTokenRepository.findById(token.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
            Instant usedAt = used.getUsedAt();
            boolean withinLeeway = usedAt != null
                && !now.isAfter(usedAt.plus(jwtProperties.refreshReuseLeeway()));
            if (!withinLeeway || used.getRevokedAt() != null) {
                refreshTokenRepository.revokeFamily(token.getUserId(), token.getFamilyId(), now);
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
        }

        User user = userRepository.findById(token.getUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        String label = deviceLabel != null ? deviceLabel : token.getDeviceLabel();
        return issueTokens(user, token.getFamilyId(), label, false);
    }

    /** 기기 세션 목록. Rotation으로 family에 토큰이 여러 개 쌓이므로 family 단위로 합쳐 기기당 1건만 돌려준다. */
    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(UUID userId) {
        Map<UUID, List<RefreshToken>> byFamily = refreshTokenRepository
            .findByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, Instant.now(clock))
            .stream()
            .collect(Collectors.groupingBy(RefreshToken::getFamilyId));
        return byFamily.entrySet().stream()
            .map(e -> {
                List<RefreshToken> tokens = e.getValue();
                Instant first = tokens.stream().map(RefreshToken::getCreatedAt).min(Comparator.naturalOrder()).orElseThrow();
                Instant last = tokens.stream().map(RefreshToken::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();
                String label = tokens.stream().max(Comparator.comparing(RefreshToken::getCreatedAt))
                    .map(RefreshToken::getDeviceLabel).orElse(null);
                return new SessionResponse(e.getKey(), label, first, last);
            })
            .sorted(Comparator.comparing(SessionResponse::createdAt).reversed())
            .toList();
    }

    /** 현재 기기 로그아웃. 이미 없거나 남의 토큰이면 아무 일도 하지 않는다(멱등). */
    @Transactional
    public void logout(UUID userId, String refreshToken) {
        refreshTokenRepository.findByTokenHash(hash(refreshToken))
            .filter(t -> t.getUserId().equals(userId))
            .ifPresent(t -> refreshTokenRepository.revokeFamily(userId, t.getFamilyId(), Instant.now(clock)));
    }

    /** 원격 로그아웃. 다른 사용자의 세션이거나 없으면 404(존재 여부를 노출하지 않는다). */
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        if (refreshTokenRepository.revokeFamily(userId, sessionId, Instant.now(clock)) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private SocialProfile verify(OAuthProvider provider, String socialToken) {
        return verifiers.stream()
            .filter(v -> v.provider() == provider)
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT))
            .verify(socialToken);
    }

    private static Instant restorableUntil(User user) {
        return user.getDeletedAt().plus(RESTORE_GRACE);
    }

    private LoginResponse issueTokens(User user, UUID familyId, String deviceLabel, boolean newUser) {
        String rawRefresh = newRawToken();
        Instant now = Instant.now(clock);
        refreshTokenRepository.save(RefreshToken.issue(
            user.getId(), familyId, hash(rawRefresh), deviceLabel, now.plus(jwtProperties.refreshTtl())));
        return new LoginResponse(
            jwtProvider.createAccessToken(user.getId(), user.getMembershipTier()),
            rawRefresh,
            jwtProperties.accessTtl().toSeconds(),
            newUser
        );
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** DB에는 원문이 아닌 SHA-256 해시만 저장한다. 토큰이 고엔트로피 난수이므로 솔트 없는 해시로 충분하다. */
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
