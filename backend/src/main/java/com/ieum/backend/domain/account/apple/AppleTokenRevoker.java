package com.ieum.backend.domain.account.apple;

import java.util.UUID;

/**
 * Apple 로그인 연결 해지(Sign in with Apple 토큰 revoke). 실패하면 예외를 던지고, 호출자가 큐에서 백오프로 재시도한다.
 * 실제 호출은 Apple 키가 있어야 하므로 이번 범위에서는 {@link UnconfiguredAppleTokenRevoker}만 있다.
 */
public interface AppleTokenRevoker {

    enum Result {
        /** 해지 완료. */
        DONE,
        /** 해지 수단이 설정되지 않아 하지 않았다(재시도 대상이 아니다). */
        SKIPPED
    }

    Result revoke(UUID userId);
}
