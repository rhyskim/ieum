package com.ieum.backend.domain.auth.service;

import com.ieum.backend.domain.auth.dto.AuthDtos.IdentityResponse;
import com.ieum.backend.domain.auth.entity.UserIdentity;
import com.ieum.backend.domain.auth.repository.UserIdentityRepository;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.auth.social.SocialTokenVerifier;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 수단(user_identities) 연결·해제. 이메일 일치로 자동 연결하지 않고, 로그인된 상태에서
 * 소셜 토큰을 다시 검증한 요청에서만 연결한다. 이미 다른 계정에 가입된 소셜 계정은 병합하지 않고 거절한다(ADR-0002).
 */
@Service
public class IdentityLinkService {

    private final List<SocialTokenVerifier> verifiers;
    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;

    public IdentityLinkService(
        List<SocialTokenVerifier> verifiers,
        UserIdentityRepository identityRepository,
        UserRepository userRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.verifiers = verifiers;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<IdentityResponse> list(UUID userId) {
        return identityRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
            .map(i -> new IdentityResponse(i.getProvider(), i.getCreatedAt()))
            .toList();
    }

    /**
     * 소셜 토큰 검증(외부 호출)은 트랜잭션 밖에서 하고, 연결은 사용자 행을 잠근 트랜잭션에서 한다.
     * 서로 다른 사용자가 같은 소셜 계정을 동시에 연결하면 사용자 행 잠금으로는 줄이 서지 않으므로
     * (provider, subject) 유니크 제약이 최종 방어선이다. 위반 시 새 트랜잭션에서 소유자를 다시 본다.
     */
    public void link(UUID userId, OAuthProvider provider, String socialToken) {
        SocialProfile profile = verify(provider, socialToken);
        try {
            tx.executeWithoutResult(s -> linkLocked(userId, provider, profile));
        } catch (DataIntegrityViolationException e) {
            Optional<UserIdentity> owner = identityRepository.findByProviderAndSubject(provider, profile.subject());
            if (owner.isPresent() && owner.get().getUser().getId().equals(userId)) {
                return; // 같은 사용자의 동시 요청이 먼저 연결했다: 멱등 성공
            }
            throw new BusinessException(ErrorCode.IDENTITY_ALREADY_REGISTERED);
        }
    }

    /** 사용자 행을 잠근 뒤 개수를 센다: 서로 다른 수단 동시 해제가 둘 다 "2개라 가능"으로 보는 것을 막는다. */
    public void unlink(UUID userId, OAuthProvider provider) {
        tx.executeWithoutResult(s -> {
            lockActiveUser(userId);
            Optional<UserIdentity> identity = identityRepository.findByUserIdAndProvider(userId, provider);
            if (identity.isEmpty()) {
                return; // 연결돼 있지 않으면 멱등 성공
            }
            if (identityRepository.countByUserId(userId) <= 1) {
                throw new BusinessException(ErrorCode.LAST_IDENTITY_CANNOT_UNLINK);
            }
            identityRepository.delete(identity.get());
        });
    }

    private void linkLocked(UUID userId, OAuthProvider provider, SocialProfile profile) {
        User user = lockActiveUser(userId);
        Optional<UserIdentity> existing = identityRepository.findByProviderAndSubject(provider, profile.subject());
        if (existing.isPresent()) {
            if (existing.get().getUser().getId().equals(userId)) {
                return;
            }
            throw new BusinessException(ErrorCode.IDENTITY_ALREADY_REGISTERED);
        }
        if (identityRepository.findByUserIdAndProvider(userId, provider).isPresent()) {
            throw new BusinessException(ErrorCode.IDENTITY_PROVIDER_ALREADY_LINKED);
        }
        identityRepository.saveAndFlush(UserIdentity.create(user, provider, profile.subject(), profile.email()));
    }

    private User lockActiveUser(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return user;
    }

    private SocialProfile verify(OAuthProvider provider, String socialToken) {
        return verifiers.stream()
            .filter(v -> v.provider() == provider)
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT))
            .verify(socialToken);
    }
}
