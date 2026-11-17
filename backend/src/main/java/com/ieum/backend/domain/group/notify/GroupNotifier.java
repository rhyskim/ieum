package com.ieum.backend.domain.group.notify;

import java.util.UUID;

/** 그룹 이벤트 알림 포트. 실제 FCM 구현은 notification 작업에서 교체한다. */
public interface GroupNotifier {

    /** 방장에게 입장 요청이 도착했음을 알린다. */
    void joinRequested(UUID groupId, UUID ownerUserId, UUID requesterUserId);
}
