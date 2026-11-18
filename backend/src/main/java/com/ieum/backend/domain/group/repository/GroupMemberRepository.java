package com.ieum.backend.domain.group.repository;

import com.ieum.backend.domain.group.entity.GroupMember;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 멤버 조회. "유효 멤버"의 정의는 {@link #VALID_USER} 한 곳뿐이다(ADR-0002 결정 11):
 * 탈퇴 유예 중(users.deleted_at 있음)인 사용자는 멤버 목록·인원 COUNT·방장 후보·승인 대기 목록·접근 검사에서 빠진다.
 * 멤버 상태 컬럼은 ACTIVE로 유지하고 users.deleted_at으로 판단한다. 상태·소속으로 목록·개수를 세는
 * 파생 쿼리 메서드는 두지 않는다(우회로 방지). 새 조회가 필요하면 이 조각을 붙여서 추가한다.
 */
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    /** 유효 멤버 조건. 쿼리의 사용자 별칭은 반드시 {@code u}(= m.user)여야 한다. */
    String VALID_USER = "u.deletedAt is null";

    String ACTIVE = "m.status = com.ieum.backend.domain.common.enums.MemberStatus.ACTIVE";
    String PENDING = "m.status = com.ieum.backend.domain.common.enums.MemberStatus.PENDING";

    @Query("select count(m) > 0 from GroupMember m join m.user u "
        + "where m.group.id = :groupId and u.id = :userId and " + ACTIVE + " and " + VALID_USER)
    boolean existsValidActive(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Query("select count(m) > 0 from GroupMember m join m.user u "
        + "where m.group.id = :groupId and u.id = :userId and m.owner = true and " + ACTIVE + " and " + VALID_USER)
    boolean existsValidActiveOwner(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /** 인원 상한(20명) 검사용. 유예 중 사용자는 -1로 반영된다. */
    @Query("select count(m) from GroupMember m join m.user u "
        + "where m.group.id = :groupId and " + ACTIVE + " and " + VALID_USER)
    long countValidActive(@Param("groupId") UUID groupId);

    /** 멤버 목록·방장 후보: 가입 일시 → id 순. */
    @EntityGraph(attributePaths = "user")
    @Query("select m from GroupMember m join m.user u "
        + "where m.group.id = :groupId and " + ACTIVE + " and " + VALID_USER
        + " order by m.joinedAt asc, m.id asc")
    List<GroupMember> findValidActive(@Param("groupId") UUID groupId);

    @EntityGraph(attributePaths = "user")
    @Query("select m from GroupMember m join m.user u "
        + "where m.group.id = :groupId and " + PENDING + " and " + VALID_USER
        + " order by m.requestedAt asc")
    List<GroupMember> findValidPending(@Param("groupId") UUID groupId);

    @EntityGraph(attributePaths = "user")
    @Query("select m from GroupMember m join m.user u "
        + "where m.group.id = :groupId and m.owner = true and " + ACTIVE + " and " + VALID_USER)
    Optional<GroupMember> findValidActiveOwner(@Param("groupId") UUID groupId);

    /** 승인·거절·강퇴 대상 조회: 유예 중 사용자는 없는 것으로 본다. */
    @EntityGraph(attributePaths = "user")
    @Query("select m from GroupMember m join m.user u "
        + "where m.group.id = :groupId and u.id = :userId and " + VALID_USER)
    Optional<GroupMember> findValid(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /** 본인의 멤버십 한 건(입장 요청·이탈처럼 호출자 본인이 대상인 흐름). 타인을 대상으로는 {@link #findValid}를 쓴다. */
    Optional<GroupMember> findByGroup_IdAndUser_Id(UUID groupId, UUID userId);

    /** 내 그룹 목록. 호출자 본인의 멤버십이므로 유효 멤버 조건 대상이 아니다. */
    @EntityGraph(attributePaths = "group")
    @Query("select m from GroupMember m where m.user.id = :userId and " + ACTIVE
        + " order by m.joinedAt asc")
    List<GroupMember> findMyActive(@Param("userId") UUID userId);

    /**
     * 탈퇴 요청: 내가 방장인 ACTIVE 그룹의 id를 오름차순으로(락 순서 고정). 엔티티가 아니라 id만 돌려주는 이유는,
     * 그룹을 잠근 뒤에 멤버십을 새로 읽어 잠금 이후의 최신 상태로 판단하기 위해서다.
     */
    @Query("select m.group.id from GroupMember m where m.user.id = :userId and m.owner = true and " + ACTIVE
        + " order by m.group.id asc")
    List<UUID> findMyOwnedActiveGroupIds(@Param("userId") UUID userId);

    /** 복구: 내 ACTIVE 그룹의 id를 오름차순으로(락 순서 고정). */
    @Query("select m.group.id from GroupMember m where m.user.id = :userId and " + ACTIVE
        + " order by m.group.id asc")
    List<UUID> findMyActiveGroupIds(@Param("userId") UUID userId);

    /**
     * 파기 전용(유효 멤버 규칙의 의도적 예외): 본인을 뺀 ACTIVE 멤버 중 아직 파기되지 않은 사람 수. 복구할 수 있는
     * 탈퇴 유예 중 멤버까지 센다. 유예 중인 멤버가 남아 있는 그룹을 먼저 파기되는 사람 때문에 해산하지 않기 위해서다.
     */
    @Query("select count(m) from GroupMember m join m.user u "
        + "where m.group.id = :groupId and u.id <> :userId and " + ACTIVE + " and u.purgedAt is null")
    long countActiveNotPurgedExcept(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /** 파기 전용: 해산하는 그룹에 남은 모든 ACTIVE 행(이미 파기된 방장 포함)을 LEFT·방장 해제로 정리한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update GroupMember m set m.status = com.ieum.backend.domain.common.enums.MemberStatus.LEFT, "
        + "m.owner = false, m.leftAt = :now where m.group.id = :groupId and " + ACTIVE)
    int leaveAllActive(@Param("groupId") UUID groupId, @Param("now") Instant now);
}
