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
}
