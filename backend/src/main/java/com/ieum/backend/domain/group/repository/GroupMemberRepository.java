package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.entity.GroupMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    boolean existsByGroup_IdAndUser_IdAndStatus(UUID groupId, UUID userId, MemberStatus status);

    boolean existsByGroup_IdAndUser_IdAndStatusAndOwnerTrue(UUID groupId, UUID userId, MemberStatus status);

    long countByGroup_IdAndStatus(UUID groupId, MemberStatus status);

    @EntityGraph(attributePaths = "user")
    List<GroupMember> findAllByGroup_IdAndStatusOrderByRequestedAtAsc(UUID groupId, MemberStatus status);

    Optional<GroupMember> findByGroup_IdAndUser_Id(UUID groupId, UUID userId);

    Optional<GroupMember> findFirstByGroup_IdAndStatusAndOwnerTrue(UUID groupId, MemberStatus status);

    @EntityGraph(attributePaths = "group")
    List<GroupMember> findAllByUser_IdAndStatusOrderByJoinedAtAsc(UUID userId, MemberStatus status);

    @EntityGraph(attributePaths = "user")
    List<GroupMember> findAllByGroup_IdAndStatusOrderByJoinedAtAscIdAsc(UUID groupId, MemberStatus status);
}
