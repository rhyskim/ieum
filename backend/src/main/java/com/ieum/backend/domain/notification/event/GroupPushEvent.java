package com.ieum.backend.domain.notification.event;

import java.util.UUID;

/** 푸시 대상 정보만 담는다. 문구·data는 리스너가 고정 문구로 만들므로 개인정보가 섞일 수 없다. */
public record GroupPushEvent(Type type, UUID groupId, UUID recipientUserId) {

    public enum Type {
        JOIN_REQUESTED,
        JOIN_APPROVED,
        JOIN_REJECTED
    }
}
