package com.ieum.backend.domain.question.repository;

import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyQuestionRepository extends JpaRepository<DailyQuestion, UUID> {

    /** 그룹이 지금까지 받은 질문의 최대 sequence_order(없으면 0). 파라미터 :groupId를 쓴다. */
    String SERVED_MAX = "coalesce((select max(q2.sequence_order) from daily_questions d "
        + "join questions q2 on q2.id = d.question_id where d.group_id = cast(:groupId as uuid)), 0)";

    /** 이 사이클에 질문이 아직 없는 ACTIVE 그룹. "질문 없음"의 기록은 로그가 아니라 daily_questions 행이다(ADR-0006). */
    @Query("select g.id from FamilyGroup g where g.status = com.ieum.backend.domain.common.enums.GroupStatus.ACTIVE "
        + "and not exists (select 1 from DailyQuestion d where d.group = g and d.cycleDate = :cycleDate) "
        + "order by g.id")
    List<UUID> findActiveGroupIdsWithoutQuestion(@Param("cycleDate") LocalDate cycleDate);

    /**
     * 그룹이 받은 질문 중 최대 sequence_order보다 큰 첫 활성 질문(받은 게 없으면 첫 활성 질문)을 배정한다(ADR-0006 결정 3).
     * 이미 받은 질문의 비활성화·삭제에 영향받지 않는다. UNIQUE(group_id, cycle_date)가 중복을 막고
     * ON CONFLICT DO NOTHING이 충돌을 에러 없는 무시(멱등)로 바꾼다. 새 행이 생기면 1, 충돌이거나
     * 남은 질문이 없으면 0을 돌려준다. 두 서버가 동시에 실행해도 한쪽만 1이다.
     */
    @Modifying
    @Query(value = "insert into daily_questions (group_id, question_id, cycle_date, reveal_status, assigned_at, auto_reveal_at) "
        + "select cast(:groupId as uuid), q.id, cast(:cycleDate as date), 'LOCKED', "
        + "cast(:assignedAt as timestamptz), cast(:autoRevealAt as timestamptz) "
        + "from questions q where q.is_active = true and q.sequence_order > " + SERVED_MAX
        + " order by q.sequence_order limit 1 "
        + "on conflict (group_id, cycle_date) do nothing", nativeQuery = true)
    int assignNext(
        @Param("groupId") UUID groupId,
        @Param("cycleDate") LocalDate cycleDate,
        @Param("assignedAt") Instant assignedAt,
        @Param("autoRevealAt") Instant autoRevealAt
    );

    boolean existsByGroup_IdAndCycleDate(UUID groupId, LocalDate cycleDate);

    /** 이 그룹이 앞으로 받을 수 있는 활성 질문 수(경고 로그용). */
    @Query(value = "select count(*) from questions q where q.is_active = true and q.sequence_order > " + SERVED_MAX,
        nativeQuery = true)
    long countRemaining(@Param("groupId") UUID groupId);

    @EntityGraph(attributePaths = "question")
    Optional<DailyQuestion> findByGroup_IdAndCycleDate(UUID groupId, LocalDate cycleDate);

    @EntityGraph(attributePaths = "question")
    Optional<DailyQuestion> findByIdAndGroup_Id(UUID id, UUID groupId);

    /**
     * 답변 저장·멤버 변동 재평가가 같은 질문에 대해 줄을 서는 행 잠금(ADR-0007 결정 1·9).
     * 락 순서 users → family_groups(id 오름차순) → daily_questions(id 오름차순)의 마지막 단계다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyQuestion d where d.id = :id and d.group.id = :groupId")
    Optional<DailyQuestion> findByIdAndGroupIdForUpdate(@Param("id") UUID id, @Param("groupId") UUID groupId);

    @Query("select d.revealStatus from DailyQuestion d where d.id = :id")
    Optional<RevealStatus> findRevealStatus(@Param("id") UUID id);

    /** 재평가 대상: 그룹의 LOCKED 질문 id(오름차순 = 락 순서). 마감이 없어 지난 질문도 포함한다. */
    @Query("select d.id from DailyQuestion d where d.group.id = :groupId "
        + "and d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED order by d.id")
    List<UUID> findLockedIdsByGroup(@Param("groupId") UUID groupId);

    /** 전원 답변 공개 전환. 1이면 내가 전환시킨 것이다(알림은 이때만). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DailyQuestion d set d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.REVEALED_ALL, "
        + "d.revealedAt = :now where d.id = :id "
        + "and d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED")
    int revealAllIfLocked(@Param("id") UUID id, @Param("now") Instant now);

    /** 시간 초과 공개 전환: 17시가 지났고 답변이 1건 이상일 때만. 0명이면 LOCKED 유지(질문 건너뜀). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DailyQuestion d set d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.REVEALED_TIMEOUT, "
        + "d.revealedAt = :now where d.id = :id "
        + "and d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED "
        + "and d.autoRevealAt <= :now and exists (select 1 from Answer a where a.dailyQuestion.id = d.id)")
    int revealTimeoutIfDue(@Param("id") UUID id, @Param("now") Instant now);

    /** 아카이브 첫 페이지: cycle_date 내림차순, id 보조 정렬(ADR-0008 결정 5). */
    @EntityGraph(attributePaths = "question")
    @Query("select d from DailyQuestion d where d.group.id = :groupId order by d.cycleDate desc, d.id desc")
    List<DailyQuestion> findArchiveFirstPage(@Param("groupId") UUID groupId, Pageable pageable);

    /** 아카이브 다음 페이지: 마지막 항목 (cycle_date, id) 보다 뒤. OFFSET이 아니라 키셋이라 새 항목이 생겨도 밀리지 않는다. */
    @EntityGraph(attributePaths = "question")
    @Query("select d from DailyQuestion d where d.group.id = :groupId "
        + "and (d.cycleDate < :cycleDate or (d.cycleDate = :cycleDate and d.id < :id)) "
        + "order by d.cycleDate desc, d.id desc")
    List<DailyQuestion> findArchiveAfter(
        @Param("groupId") UUID groupId,
        @Param("cycleDate") LocalDate cycleDate,
        @Param("id") UUID id,
        Pageable pageable
    );

    @Query("select d.group.id from DailyQuestion d where d.id = :id")
    Optional<UUID> findGroupId(@Param("id") UUID id);

    /** 17시 공개 작업 후보: 마감이 지났고 답변이 1건 이상인 LOCKED 질문(ACTIVE 그룹). id 오름차순 = 락 순서(ADR-0007 결정 9). */
    @Query("select d.id from DailyQuestion d where d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED "
        + "and d.autoRevealAt <= :now and d.group.status = com.ieum.backend.domain.common.enums.GroupStatus.ACTIVE "
        + "and exists (select 1 from Answer a where a.dailyQuestion.id = d.id) order by d.id")
    List<UUID> findTimeoutRevealCandidateIds(@Param("now") Instant now);

    /** 15시 리마인드 후보: 마감 전 2시간 안에 들어온 LOCKED 질문 중 아직 리마인드를 보내지 않은 것(ACTIVE 그룹). */
    @Query("select d.id from DailyQuestion d where d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED "
        + "and d.reminderSent = false and d.autoRevealAt > :now and d.autoRevealAt <= :windowEnd "
        + "and d.group.status = com.ieum.backend.domain.common.enums.GroupStatus.ACTIVE order by d.id")
    List<UUID> findReminderCandidateIds(@Param("now") Instant now, @Param("windowEnd") Instant windowEnd);

    /** 리마인드 발송권 획득. 1이면 내가 가져간 것이고 그때만 푸시한다. 시각 조건이 UPDATE 안에 있어 마감 후에는 0이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DailyQuestion d set d.reminderSent = true where d.id = :id and d.reminderSent = false "
        + "and d.revealStatus = com.ieum.backend.domain.common.enums.RevealStatus.LOCKED "
        + "and d.autoRevealAt > :now and d.autoRevealAt <= :windowEnd")
    int markReminderSent(@Param("id") UUID id, @Param("now") Instant now, @Param("windowEnd") Instant windowEnd);
}
