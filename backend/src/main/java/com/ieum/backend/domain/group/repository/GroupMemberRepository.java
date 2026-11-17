package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.entity.GroupMember;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    boolean existsByGroup_IdAndUser_IdAndStatus(UUID groupId, UUID userId, MemberStatus status);
}
