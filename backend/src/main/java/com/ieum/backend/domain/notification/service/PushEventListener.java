package com.ieum.backend.domain.notification.service;

import com.ieum.backend.domain.notification.config.PushAsyncConfig;
import com.ieum.backend.domain.notification.config.PushProperties;
import com.ieum.backend.domain.notification.event.GroupPushEvent;
import com.ieum.backend.domain.notification.push.PushMessage;
import com.ieum.backend.domain.notification.push.PushResult;
import com.ieum.backend.domain.notification.push.PushSender;
import com.ieum.backend.domain.user.repository.UserDeviceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋이 끝난 뒤(롤백되면 호출되지 않는다) 푸시 전용 스레드에서 발송한다(ADR-0004).
 * 트랜잭션 밖에서 발행된 이벤트는 fallbackExecution으로 즉시 처리한다.
 * 어떤 예외도 밖으로 던지지 않으며 업무 트랜잭션·응답에 영향을 주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushEventListener {

    private final UserDeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final PushSender sender;
    private final PushProperties properties;

    @Async(PushAsyncConfig.PUSH_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(GroupPushEvent event) {
        try {
            PushMessage message = messageOf(event);
            List<String> tokens = deviceRepository.findTokensByUserId(event.recipientUserId());
            for (String token : tokens) {
                sendToToken(token, message);
            }
        } catch (RuntimeException e) {
            log.warn("푸시 처리 실패: type={}", event.type(), e);
        }
    }

    private void sendToToken(String token, PushMessage message) {
        try {
            PushResult result = sendWithRetry(token, message);
            if (result == PushResult.UNREGISTERED) {
                deviceService.deleteToken(token);
            }
        } catch (RuntimeException e) {
            log.warn("푸시 발송 실패(다음 기기로 계속): {}", e.toString());
        }
    }

    /** 일시 오류(RETRYABLE)만 최대 maxAttempts회까지, 고정 간격으로 재시도한다. */
    private PushResult sendWithRetry(String token, PushMessage message) {
        PushResult result = PushResult.FAILED;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            result = sender.send(token, message);
            if (result != PushResult.RETRYABLE) {
                return result;
            }
            if (attempt < properties.maxAttempts()) {
                pause();
            }
        }
        log.warn("푸시 일시 오류가 계속되어 포기합니다(시도 {}회).", properties.maxAttempts());
        return result;
    }

    private void pause() {
        try {
            Thread.sleep(properties.retryBackoff().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 잠금 화면에 보이므로 이름·호칭·메시지를 넣지 않고 고정 문구와 식별자만 쓴다. */
    private PushMessage messageOf(GroupPushEvent event) {
        UUID groupId = event.groupId();
        Map<String, String> data = Map.of("type", event.type().name(), "groupId", groupId.toString());
        return switch (event.type()) {
            case JOIN_REQUESTED -> new PushMessage("이음", "가족 방에 새 입장 요청이 있어요", data);
            case JOIN_APPROVED -> new PushMessage("이음", "가족 방 입장 요청이 승인되었어요", data);
            case JOIN_REJECTED -> new PushMessage("이음", "가족 방 입장 요청 결과가 도착했어요", data);
        };
    }
}
