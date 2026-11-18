package com.ieum.backend.global.security;

import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupAccessValidator {

    private final GroupMemberRepository groupMemberRepository;

    public void validateActiveMember(UUID groupId, UUID userId) {
        if (groupId == null || userId == null) {
            throw new BusinessException(ErrorCode.GROUP_ACCESS_DENIED);
        }
        boolean isActiveMember = groupMemberRepository.existsValidActive(groupId, userId);
        if (!isActiveMember) {
            throw new BusinessException(ErrorCode.GROUP_ACCESS_DENIED);
        }
    }
}
