package com.ieum.backend.global.common;

import com.ieum.backend.global.exception.ErrorCode;
import java.time.Instant;

public record ApiResponse<T>(
    String code,
    String message,
    T data,
    Instant timestamp
) {
    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, DEFAULT_SUCCESS_MESSAGE, data, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(SUCCESS_CODE, message, data, Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(errorCode.getCode(), customMessage, null, Instant.now());
    }
}
