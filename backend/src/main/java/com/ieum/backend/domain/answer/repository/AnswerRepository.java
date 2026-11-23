package com.ieum.backend.domain.answer.repository;

import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {

    Optional<Answer> findByDailyQuestion_IdAndUser_Id(UUID dailyQuestionId, UUID userId);

    /**
     * 아직 답하지 않은 유효 멤버 수(분모 = 유효 멤버, ADR-0002·0007). 유효 멤버 조건은 {@link GroupMemberRepository#VALID_USER}
     * 한 곳에서만 정의한다. 나간 사람의 답변은 분모에도 분자에도 들어오지 않는다.
     */
    @Query("select count(m) from GroupMember m join m.user u where m.group.id = :groupId and "
        + GroupMemberRepository.ACTIVE + " and " + GroupMemberRepository.VALID_USER
        + " and not exists (select 1 from Answer a where a.dailyQuestion.id = :dqId and a.user.id = u.id)")
    long countUnansweredValidMembers(@Param("dqId") UUID dqId, @Param("groupId") UUID groupId);

    /** 마스킹과 무관하게 안전한 정보(누가 답했는지)만 돌려준다: 본문을 읽지 않는다. */
    @Query("select a.user.id from Answer a where a.dailyQuestion.id = :dqId")
    List<UUID> findAnsweredUserIds(@Param("dqId") UUID dqId);

    @EntityGraph(attributePaths = "user")
    @Query("select a from Answer a where a.dailyQuestion.id = :dqId order by a.createdAt asc, a.id asc")
    List<Answer> findAllWithUser(@Param("dqId") UUID dqId);

    @EntityGraph(attributePaths = "user")
    @Query("select a from Answer a where a.dailyQuestion.id = :dqId and a.user.id = :userId")
    List<Answer> findMineWithUser(@Param("dqId") UUID dqId, @Param("userId") UUID userId);

    Optional<Answer> findByIdAndGroup_Id(UUID id, UUID groupId);

    boolean existsByDailyQuestion_IdAndUser_Id(UUID dailyQuestionId, UUID userId);

    /** 타임라인: 내가 답한 질문 id만(본문을 읽지 않는다). */
    @Query("select a.dailyQuestion.id from Answer a where a.dailyQuestion.id in :dqIds and a.user.id = :userId")
    List<UUID> findAnsweredDqIds(@Param("dqIds") Collection<UUID> dqIds, @Param("userId") UUID userId);

    /** 타임라인: 열람 권한이 있는 질문들의 전체 답변. 권한이 없는 질문은 이 메서드에 넘기지 않는다. */
    @EntityGraph(attributePaths = "user")
    @Query("select a from Answer a where a.dailyQuestion.id in :dqIds order by a.createdAt asc, a.id asc")
    List<Answer> findAllWithUserByDqIds(@Param("dqIds") Collection<UUID> dqIds);

    /** 타임라인: 열람 권한이 없는 질문들에서는 내 답변만 읽는다. */
    @EntityGraph(attributePaths = "user")
    @Query("select a from Answer a where a.dailyQuestion.id in :dqIds and a.user.id = :userId "
        + "order by a.createdAt asc, a.id asc")
    List<Answer> findMineWithUserByDqIds(@Param("dqIds") Collection<UUID> dqIds, @Param("userId") UUID userId);
}
