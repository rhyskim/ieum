package com.ieum.backend.domain.group.notify;

import java.util.UUID;

/**
 * 그룹 이벤트 알림 포트. 구현은 푸시 이벤트를 발행하고(PushGroupNotifier),
 * 실제 발송은 커밋 후 비동기로 일어난다(ADR-0004). 호출자는 발송 실패를 알 필요가 없다.
 */
public interface GroupNotifier {

    /** 방장에게 입장 요청이 도착했음을 알린다. */
    void joinRequested(UUID groupId, UUID ownerUserId, UUID requesterUserId);

    /** 요청자에게 입장이 승인되었음을 알린다. */
    void joinApproved(UUID groupId, UUID requesterUserId);

    /** 요청자에게 입장이 거절되었음을 알린다. */
    void joinRejected(UUID groupId, UUID requesterUserId);
}
