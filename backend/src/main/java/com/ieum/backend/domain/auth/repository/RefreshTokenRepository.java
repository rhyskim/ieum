package com.ieum.backend.domain.auth.repository;

import com.ieum.backend.domain.auth.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 기기(family)별 현재 세션: 아직 사용·폐기되지 않았고 만료 전인 토큰. */
    List<RefreshToken> findByUserIdAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

    /** family 전체 폐기. 소유자(userId)가 일치할 때만 동작하므로 다른 사용자의 세션은 건드릴 수 없다. */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.userId = :userId and t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID userId, UUID familyId, Instant now);

    /** 원자적 사용 처리. 이미 사용됐으면 0행이 갱신되므로 동시 요청 중 정확히 한 건만 성공한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.usedAt = :now, t.lastUsedAt = :now "
        + "where t.id = :id and t.usedAt is null")
    int markUsed(UUID id, Instant now);

    /** 사용자의 모든 Refresh 폐기(탈퇴). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUser(UUID userId, Instant now);
}
