package com.ieum.backend.domain.group.notify;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingGroupNotifier implements GroupNotifier {

    @Override
    public void joinRequested(UUID groupId, UUID ownerUserId, UUID requesterUserId) {
        log.info("입장 요청 알림(로그 전용): groupId={}, ownerUserId={}, requesterUserId={}",
            groupId, ownerUserId, requesterUserId);
    }
}
