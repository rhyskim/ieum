package com.ieum.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 입력값이 올바르지 않습니다."),
    VOICE_DURATION_EXCEEDED(HttpStatus.BAD_REQUEST, "녹음 제한 시간을 초과했습니다."),
    VOICE_FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "음성 파일 최대 용량을 초과했습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 로그인 토큰이 유효하지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다."),
    SOCIAL_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "소셜 로그인 제공자 서버에 연결할 수 없습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요."),
    ACCOUNT_PENDING_DELETION(HttpStatus.FORBIDDEN, "탈퇴 처리 중인 계정입니다. 복구하려면 확인이 필요합니다."),
    ACCOUNT_RESTORE_EXPIRED(HttpStatus.FORBIDDEN, "복구 가능 기간이 지난 계정입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    GROUP_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 가족 그룹의 멤버만 접근할 수 있습니다."),
    NOT_GROUP_OWNER(HttpStatus.FORBIDDEN, "그룹 방장만 할 수 있는 작업입니다."),
    INVITE_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "입장 코드를 찾을 수 없어요. 코드를 다시 확인해 주세요."),
    INVITE_CODE_EXPIRED(HttpStatus.GONE, "입장 코드의 유효 시간이 지났어요. 방장에게 새 코드를 받아 주세요."),
    JOIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 코드로는 입장할 수 없어요. 방장에게 문의해 주세요."),
    FEATURE_NOT_ENTITLED(HttpStatus.FORBIDDEN, "현재 요금제에서 사용할 수 없는 기능입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 Accept 미디어 타입입니다."),
    INVALID_AUDIO_STATE_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 음성 상태 변경 요청입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    public String getCode() {
        return this.name();
    }
}
