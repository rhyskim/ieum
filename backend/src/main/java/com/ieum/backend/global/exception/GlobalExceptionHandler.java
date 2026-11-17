package com.ieum.backend.global.exception;

import com.ieum.backend.domain.auth.service.AccountPendingDeletionException;
import com.ieum.backend.domain.group.service.JoinLockedException;
import com.ieum.backend.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException [{}]: {}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(errorCode, e.getMessage()));
    }

    @ExceptionHandler(AccountPendingDeletionException.class)
    public ResponseEntity<ApiResponse<Map<String, Instant>>> handleAccountPendingDeletion(
        AccountPendingDeletionException e
    ) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(new ApiResponse<>(
                errorCode.getCode(),
                errorCode.getMessage(),
                Map.of("restorableUntil", e.getRestorableUntil()),
                Instant.now()
            ));
    }

    @ExceptionHandler(JoinLockedException.class)
    public ResponseEntity<ApiResponse<Map<String, Long>>> handleJoinLocked(JoinLockedException e) {
        ErrorCode errorCode = e.getErrorCode();
        long seconds = e.getRetryAfter().getSeconds();
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .header("Retry-After", String.valueOf(seconds))
            .body(new ApiResponse<>(
                errorCode.getCode(),
                e.getMessage(),
                Map.of("retryAfterSeconds", seconds),
                Instant.now()
            ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null && fieldError.getDefaultMessage() != null
            ? fieldError.getDefaultMessage()
            : ErrorCode.INVALID_INPUT.getMessage();
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    @ExceptionHandler({
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingPathVariableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequestExceptions(Exception e) {
        log.warn("Bad request exception: {}", e.getMessage());
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity
            .status(ErrorCode.ACCESS_DENIED.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity
            .status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity
            .status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity
            .status(ErrorCode.NOT_ACCEPTABLE.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.NOT_ACCEPTABLE));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity
            .status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        log.error("Unhandled internal server error", e);
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
