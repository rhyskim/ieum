package com.ieum.backend.domain.account.apple;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Apple 키가 없는 동안의 기본 구현: 호출하지 않고 "건너뜀"으로 기록되게 한다. 키가 연결되면 이 빈을 대체한다. */
@Component
public class UnconfiguredAppleTokenRevoker implements AppleTokenRevoker {

    @Override
    public Result revoke(UUID userId) {
        return Result.SKIPPED;
    }
}
