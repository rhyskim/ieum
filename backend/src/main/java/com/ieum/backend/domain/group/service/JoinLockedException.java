package com.ieum.backend.domain.group.service;

import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Duration;
import lombok.Getter;

/** 입장 시도 잠금(429). 남은 시간을 메시지와 응답 데이터에 담는다. */
@Getter
public class JoinLockedException extends BusinessException {

    private final Duration retryAfter;

    public JoinLockedException(Duration retryAfter) {
        super(ErrorCode.JOIN_LOCKED, "입장 시도가 너무 많아요. " + minutesCeil(retryAfter) + "분 뒤에 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    private static long minutesCeil(Duration d) {
        return Math.max(1, (d.getSeconds() + 59) / 60);
    }
}
