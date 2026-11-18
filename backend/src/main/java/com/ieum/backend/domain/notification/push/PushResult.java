package com.ieum.backend.domain.notification.push;

public enum PushResult {
    SENT,
    /** FCM이 토큰 만료·삭제를 알려 줌. 호출자가 토큰을 삭제한다. */
    UNREGISTERED,
    /** UNAVAILABLE·INTERNAL 같은 일시 오류. 호출자가 제한적으로 재시도한다. */
    RETRYABLE,
    FAILED,
    /** Firebase 비활성 환경. */
    SKIPPED
}
