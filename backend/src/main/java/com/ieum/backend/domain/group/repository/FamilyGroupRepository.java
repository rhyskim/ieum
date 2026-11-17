package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.group.entity.FamilyGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyGroupRepository extends JpaRepository<FamilyGroup, UUID> {
}
