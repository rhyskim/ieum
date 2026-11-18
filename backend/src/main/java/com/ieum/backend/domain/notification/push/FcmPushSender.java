package com.ieum.backend.domain.notification.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "firebase", name = "enabled", havingValue = "true")
public class FcmPushSender implements PushSender {

    @Override
    public PushResult send(String fcmToken, PushMessage message) {
        try {
            FirebaseMessaging.getInstance().send(Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder()
                    .setTitle(message.title())
                    .setBody(message.body())
                    .build())
                .putAllData(message.data())
                .build());
            return PushResult.SENT;
        } catch (FirebaseMessagingException e) {
            PushResult result = classify(e.getMessagingErrorCode());
            if (result != PushResult.UNREGISTERED) {
                log.warn("FCM 발송 실패: code={}, result={}", e.getMessagingErrorCode(), result);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("FCM 발송 중 예기치 않은 오류", e);
            return PushResult.FAILED;
        }
    }

    /** UNREGISTERED는 토큰 삭제, UNAVAILABLE·INTERNAL만 재시도 대상, 나머지는 재시도 없이 실패(ADR-0004). */
    public static PushResult classify(MessagingErrorCode code) {
        if (code == null) {
            return PushResult.FAILED;
        }
        return switch (code) {
            case UNREGISTERED -> PushResult.UNREGISTERED;
            case UNAVAILABLE, INTERNAL -> PushResult.RETRYABLE;
            default -> PushResult.FAILED;
        };
    }
}
