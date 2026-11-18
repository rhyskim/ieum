package com.ieum.backend.domain.notification.event;

import com.ieum.backend.domain.group.notify.GroupNotifier;
import com.ieum.backend.domain.notification.event.GroupPushEvent.Type;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PushGroupNotifier implements GroupNotifier {

    private final ApplicationEventPublisher publisher;

    @Override
    public void joinRequested(UUID groupId, UUID ownerUserId, UUID requesterUserId) {
        publisher.publishEvent(new GroupPushEvent(Type.JOIN_REQUESTED, groupId, ownerUserId));
    }

    @Override
    public void joinApproved(UUID groupId, UUID requesterUserId) {
        publisher.publishEvent(new GroupPushEvent(Type.JOIN_APPROVED, groupId, requesterUserId));
    }

    @Override
    public void joinRejected(UUID groupId, UUID requesterUserId) {
        publisher.publishEvent(new GroupPushEvent(Type.JOIN_REJECTED, groupId, requesterUserId));
    }
}
