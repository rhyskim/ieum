package com.ieum.backend.domain.notification.service;

import com.ieum.backend.domain.common.enums.DeviceOs;
import com.ieum.backend.domain.user.repository.UserDeviceRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    /** UNREGISTERED 토큰 삭제. 호출 맥락과 무관하게 독립 트랜잭션으로 커밋한다(ADR-0004). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteToken(String fcmToken) {
        deviceRepository.deleteByFcmToken(fcmToken);
    }
}
