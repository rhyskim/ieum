package com.ieum.backend.domain.common.enums;

/** 탈퇴 시 콘텐츠 처리 선택. 요청 시 저장하고 파기 시점에 실행한다(ADR-0002). */
public enum ContentChoice {
    /** 상대에게 계속 공개 */
    KEEP,
    /** 영구 삭제 */
    DELETE
}
