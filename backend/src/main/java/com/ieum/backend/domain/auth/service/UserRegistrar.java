package com.ieum.backend.domain.auth.service;

import com.ieum.backend.domain.auth.entity.UserIdentity;
import com.ieum.backend.domain.auth.repository.UserIdentityRepository;
import com.ieum.backend.domain.auth.social.SocialProfile;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 가입을 별도 트랜잭션으로 수행한다. 동시 가입에서 유니크 제약 위반이 나면 이 트랜잭션만 롤백되고
 * 호출자가 (provider, subject)로 재조회한다.
 */
@Component
@RequiredArgsConstructor
class UserRegistrar {

    private static final String DEFAULT_NAME = "이음 사용자";
    private static final int NAME_MAX = 50;

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User register(OAuthProvider provider, SocialProfile profile, String clientProvidedName) {
        String name = firstNonBlank(profile.name(), clientProvidedName, DEFAULT_NAME);
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX);
        }
        User user = userRepository.save(User.create(profile.email(), name, null));
        identityRepository.saveAndFlush(UserIdentity.create(user, provider, profile.subject(), profile.email()));
        return user;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return DEFAULT_NAME;
    }
}
