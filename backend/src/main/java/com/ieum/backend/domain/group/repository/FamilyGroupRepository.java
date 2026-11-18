package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.group.entity.FamilyGroup;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyGroupRepository extends JpaRepository<FamilyGroup, UUID> {

    /** 승인·강퇴·이탈이 같은 그룹 행 잠금(SELECT ... FOR UPDATE)으로 줄을 선다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from FamilyGroup g where g.id = :id")
    Optional<FamilyGroup> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 답변 저장용 공유 잠금(FOR SHARE): 승인·강퇴·이탈(FOR UPDATE)과는 줄을 서지만 저장끼리는 막지 않는다.
     * 답변 INSERT의 group_id FK KEY SHARE가 이 순서로 미리 잡혀 있어야 교착이 나지 않는다(ADR-0007 결정 9).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select g from FamilyGroup g where g.id = :id")
    Optional<FamilyGroup> findByIdForShare(@Param("id") UUID id);
}
