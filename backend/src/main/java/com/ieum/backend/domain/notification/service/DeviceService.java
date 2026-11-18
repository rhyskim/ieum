package com.ieum.backend.domain.notification.service;

import com.ieum.backend.domain.common.enums.DeviceOs;
import com.ieum.backend.domain.user.repository.UserDeviceRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final UserDeviceRepository deviceRepository;

    /** 멱등 등록: 같은 (user, token)이면 OS와 last_active_at만 갱신한다. */
    @Transactional
    public void register(UUID userId, String fcmToken, DeviceOs deviceOs) {
        deviceRepository.upsert(userId, fcmToken, deviceOs.name(), Instant.now());
    }
}
