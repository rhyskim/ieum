package com.ieum.backend.domain.group.entity;

import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Entity
@Table(name = "group_invites")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupInvite extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FamilyGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    @JdbcTypeCode(Types.CHAR)
    @Column(name = "invite_code", length = 6, nullable = false, columnDefinition = "char(6)")
    private String inviteCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public static GroupInvite create(
        FamilyGroup group,
        User inviter,
        String inviteCode,
        Instant expiresAt
    ) {
        GroupInvite invite = new GroupInvite();
        invite.group = group;
        invite.inviter = inviter;
        invite.inviteCode = inviteCode;
        invite.expiresAt = expiresAt;
        invite.active = true;
        return invite;
    }

    public void deactivate() {
        this.active = false;
    }
}
