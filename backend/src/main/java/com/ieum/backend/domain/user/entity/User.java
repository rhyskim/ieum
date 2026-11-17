package com.ieum.backend.domain.user.entity;

import com.ieum.backend.domain.common.enums.FontScaleMode;
import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_role", length = 20, nullable = false)
    private MemberRole defaultRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "font_scale_mode", length = 20, nullable = false)
    private FontScaleMode fontScaleMode;

    @Column(name = "high_contrast_enabled", nullable = false)
    private boolean highContrastEnabled;

    @Column(name = "voice_consent_agreed", nullable = false)
    private boolean voiceConsentAgreed;

    @Column(name = "voice_consent_agreed_at")
    private Instant voiceConsentAgreedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_tier", length = 20, nullable = false)
    private MembershipTier membershipTier;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static User create(
        String email,
        String name,
        MemberRole defaultRole
    ) {
        User user = new User();
        user.email = email;
        user.name = name;
        user.defaultRole = defaultRole != null ? defaultRole : MemberRole.CHILD;
        user.fontScaleMode = user.defaultRole == MemberRole.PARENT ? FontScaleMode.LARGE : FontScaleMode.NORMAL;
        user.highContrastEnabled = user.defaultRole == MemberRole.PARENT;
        user.voiceConsentAgreed = false;
        user.membershipTier = MembershipTier.FREE;
        return user;
    }

    public void markDeleted(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void restore() {
        this.deletedAt = null;
    }

    public void agreeVoiceConsent(Instant agreedAt) {
        this.voiceConsentAgreed = true;
        this.voiceConsentAgreedAt = agreedAt;
    }

    public void updateAccessibilitySettings(FontScaleMode fontScaleMode, boolean highContrastEnabled) {
        this.fontScaleMode = fontScaleMode;
        this.highContrastEnabled = highContrastEnabled;
    }
}
