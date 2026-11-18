package com.ieum.backend.domain.notification.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** firebase.enabled=false(기본, 키 없는 환경)에서 발송을 건너뛴다. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "firebase", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopPushSender implements PushSender {

    @Override
    public PushResult send(String fcmToken, PushMessage message) {
        log.debug("Firebase 비활성: 푸시 발송을 건너뜁니다.");
        return PushResult.SKIPPED;
    }
}
