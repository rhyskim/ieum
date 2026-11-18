package com.ieum.backend.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.backend.domain.account.apple.AppleTokenRevoker;
import com.ieum.backend.domain.account.apple.UnconfiguredAppleTokenRevoker;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnconfiguredAppleTokenRevokerTest {

    @Test
    @DisplayName("키가 없는 기본 구현체는 호출 없이 SKIPPED를 돌려준다(재시도 대상이 아니다)")
    void returnsSkippedWithoutCalling() {
        assertThat(new UnconfiguredAppleTokenRevoker().revoke(UUID.randomUUID()))
            .isEqualTo(AppleTokenRevoker.Result.SKIPPED);
    }
}
