package com.ieum.backend.domain.user.entity;

import com.ieum.backend.domain.common.enums.DeviceOs;
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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "user_devices",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_fcm_token", columnNames = {"fcm_token"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "fcm_token", length = 512, nullable = false)
    private String fcmToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_os", length = 20, nullable = false)
    private DeviceOs deviceOs;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    public static UserDevice create(User user, String fcmToken, DeviceOs deviceOs, Instant now) {
        UserDevice device = new UserDevice();
        device.user = user;
        device.fcmToken = fcmToken;
        device.deviceOs = deviceOs;
        device.lastActiveAt = now;
        return device;
    }

    public void touchLastActiveAt(Instant now) {
        this.lastActiveAt = now;
    }
}
