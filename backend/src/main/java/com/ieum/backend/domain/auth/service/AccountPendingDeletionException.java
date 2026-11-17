package com.ieum.backend.domain.auth.service;

import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Instant;
import lombok.Getter;

/** 탈퇴 유예 중인 계정의 로그인. 응답 data에 restorableUntil을 담기 위해 별도 예외로 둔다. */
@Getter
public class AccountPendingDeletionException extends BusinessException {

    private final Instant restorableUntil;

    public AccountPendingDeletionException(Instant restorableUntil) {
        super(ErrorCode.ACCOUNT_PENDING_DELETION);
        this.restorableUntil = restorableUntil;
    }
}
