package com.ieum.backend.domain.auth.entity;

import com.ieum.backend.domain.common.enums.OAuthProvider;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "user_identities",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_user_identities_provider_subject",
        columnNames = {"provider", "subject"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, nullable = false)
    private OAuthProvider provider;

    @Column(name = "subject", length = 128, nullable = false)
    private String subject;

    @Column(name = "email", length = 255)
    private String email;

    public static UserIdentity create(User user, OAuthProvider provider, String subject, String email) {
        UserIdentity identity = new UserIdentity();
        identity.user = user;
        identity.provider = provider;
        identity.subject = subject;
        identity.email = email;
        return identity;
    }
}
