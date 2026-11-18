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
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                return PushResult.UNREGISTERED;
            }
            log.warn("FCM 발송 실패: code={}", e.getMessagingErrorCode(), e);
            return PushResult.FAILED;
        } catch (RuntimeException e) {
            log.warn("FCM 발송 중 예기치 않은 오류", e);
            return PushResult.FAILED;
        }
    }
}
