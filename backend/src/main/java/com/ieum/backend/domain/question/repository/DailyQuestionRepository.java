package com.ieum.backend.domain.question.repository;

import com.ieum.backend.domain.question.entity.DailyQuestion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyQuestionRepository extends JpaRepository<DailyQuestion, UUID> {

    /** 이 사이클에 질문이 아직 없는 ACTIVE 그룹. "질문 없음"의 기록은 로그가 아니라 daily_questions 행이다(ADR-0006). */
    @Query("select g.id from FamilyGroup g where g.status = com.ieum.backend.domain.common.enums.GroupStatus.ACTIVE "
        + "and not exists (select 1 from DailyQuestion d where d.group = g and d.cycleDate = :cycleDate) "
        + "order by g.id")
    List<UUID> findActiveGroupIdsWithoutQuestion(@Param("cycleDate") LocalDate cycleDate);

    /**
     * 그룹이 받은 질문 수 + 1번째(활성 질문 중 sequence_order 순)를 배정한다. UNIQUE(group_id, cycle_date)가
     * 중복을 막고 ON CONFLICT DO NOTHING이 충돌을 에러 없는 무시(멱등)로 바꾼다. 새 행이 생기면 1, 충돌이거나
     * 남은 질문이 없으면 0을 돌려준다. 두 서버가 동시에 실행해도 한쪽만 1이다.
     */
    @Modifying
    @Query(value = "insert into daily_questions (group_id, question_id, cycle_date, reveal_status, assigned_at, auto_reveal_at) "
        + "select cast(:groupId as uuid), q.id, cast(:cycleDate as date), 'LOCKED', "
        + "cast(:assignedAt as timestamptz), cast(:autoRevealAt as timestamptz) "
        + "from questions q where q.is_active = true "
        + "order by q.sequence_order "
        + "offset (select count(*) from daily_questions d where d.group_id = cast(:groupId as uuid)) limit 1 "
        + "on conflict (group_id, cycle_date) do nothing", nativeQuery = true)
    int assignNext(
        @Param("groupId") UUID groupId,
        @Param("cycleDate") LocalDate cycleDate,
        @Param("assignedAt") Instant assignedAt,
        @Param("autoRevealAt") Instant autoRevealAt
    );

    boolean existsByGroup_IdAndCycleDate(UUID groupId, LocalDate cycleDate);

    long countByGroup_Id(UUID groupId);

    @EntityGraph(attributePaths = "question")
    Optional<DailyQuestion> findByGroup_IdAndCycleDate(UUID groupId, LocalDate cycleDate);
}
