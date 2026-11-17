package com.ieum.backend.domain.auth.repository;

import com.ieum.backend.domain.auth.entity.UserIdentity;
import com.ieum.backend.domain.common.enums.OAuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndSubject(OAuthProvider provider, String subject);
}
