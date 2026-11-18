package com.ieum.backend.domain.answer.repository;

import com.ieum.backend.domain.answer.entity.Answer;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
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
}
