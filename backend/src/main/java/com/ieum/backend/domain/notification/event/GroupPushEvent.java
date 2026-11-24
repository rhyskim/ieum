package com.ieum.backend.domain.notification.event;

import java.util.UUID;

/**
 * 푸시 대상 정보만 담는다. body가 null이면 리스너가 type별 고정 문구를 쓰고, 값이 있으면(17시 공개처럼 수신자별 문구)
 * 그 문구를 쓴다. 문구는 호칭만 포함하며 답변 내용은 어떤 경우에도 들어가지 않는다(ADR-0004 결정 3).
 */
public record GroupPushEvent(Type type, UUID groupId, UUID recipientUserId, String body) {

    public GroupPushEvent(Type type, UUID groupId, UUID recipientUserId) {
        this(type, groupId, recipientUserId, null);
    }

    public enum Type {
        JOIN_REQUESTED,
        JOIN_APPROVED,
        JOIN_REJECTED,
        QUESTION_ASSIGNED,
        ALL_ANSWERED,
        REMINDER,
        REVEALED_TIMEOUT
    }
}
