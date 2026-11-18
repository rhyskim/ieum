package com.ieum.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.messaging.MessagingErrorCode;
import com.ieum.backend.domain.notification.push.FcmPushSender;
import com.ieum.backend.domain.notification.push.PushResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FcmErrorClassificationTest {

    @Test
    @DisplayName("UNREGISTERED는 토큰 삭제 대상, UNAVAILABLE·INTERNAL만 재시도 대상, 나머지는 실패(재시도 없음)")
    void classify() {
        assertThat(FcmPushSender.classify(MessagingErrorCode.UNREGISTERED)).isEqualTo(PushResult.UNREGISTERED);
        assertThat(FcmPushSender.classify(MessagingErrorCode.UNAVAILABLE)).isEqualTo(PushResult.RETRYABLE);
        assertThat(FcmPushSender.classify(MessagingErrorCode.INTERNAL)).isEqualTo(PushResult.RETRYABLE);
        assertThat(FcmPushSender.classify(MessagingErrorCode.INVALID_ARGUMENT)).isEqualTo(PushResult.FAILED);
        assertThat(FcmPushSender.classify(MessagingErrorCode.QUOTA_EXCEEDED)).isEqualTo(PushResult.FAILED);
        assertThat(FcmPushSender.classify(null)).isEqualTo(PushResult.FAILED);
    }
}
