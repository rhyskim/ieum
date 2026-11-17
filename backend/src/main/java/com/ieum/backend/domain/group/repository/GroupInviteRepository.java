package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.group.entity.GroupInvite;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupInviteRepository extends JpaRepository<GroupInvite, UUID> {

    @EntityGraph(attributePaths = "group")
    Optional<GroupInvite> findByInviteCodeAndActiveTrue(String inviteCode);

    Optional<GroupInvite> findByGroup_IdAndActiveTrue(UUID groupId);

    /** 삽입보다 먼저 실행되어야 하므로(부분 유니크 인덱스) 벌크 UPDATE로 즉시 반영한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update GroupInvite i set i.active = false where i.group.id = :groupId and i.active = true")
    int deactivateActiveByGroupId(@Param("groupId") UUID groupId);
}
