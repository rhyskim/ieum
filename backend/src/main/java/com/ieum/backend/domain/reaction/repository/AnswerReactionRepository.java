package com.ieum.backend.domain.reaction.repository;

import com.ieum.backend.domain.answer.entity.AnswerReaction;
import com.ieum.backend.domain.common.enums.EmojiCode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 이모지 리액션 전용 쿼리. 음성 리액션 행(reaction_type=VOICE_MESSAGE)은 모두 제외한다(Phase 2). */
public interface AnswerReactionRepository extends JpaRepository<AnswerReaction, UUID> {

    String EMOJI = "r.reactionType = com.ieum.backend.domain.common.enums.ReactionType.EMOJI";

    interface CountRow {
        UUID getAnswerId();
        EmojiCode getEmojiCode();
        long getCnt();
    }

    interface MineRow {
        UUID getAnswerId();
        EmojiCode getEmojiCode();
    }

    /**
     * 달기. 이미 있으면 아무 일도 하지 않는다(멱등, ADR-0008 결정 2). 중복 방지의 최종 방어선은 DB 유일 인덱스이고
     * 이 문장은 충돌을 에러 없는 무시로 바꾼다. 새로 만들어졌으면 1, 이미 있었으면 0.
     */
    @Modifying
    @Query(value = "insert into answer_reactions (answer_id, user_id, reaction_type, emoji_code, voice_status) "
        + "values (:answerId, :userId, 'EMOJI', :emoji, 'NONE') on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("answerId") UUID answerId, @Param("userId") UUID userId, @Param("emoji") String emoji);

    /** 취소. 없으면 0행이라 그대로 성공한다(멱등). */
    @Modifying
    @Query("delete from AnswerReaction r where r.answer.id = :answerId and r.user.id = :userId "
        + "and r.emojiCode = :emoji and " + EMOJI)
    int deleteEmoji(@Param("answerId") UUID answerId, @Param("userId") UUID userId, @Param("emoji") EmojiCode emoji);

    @Query("select r.answer.id as answerId, r.emojiCode as emojiCode, count(r) as cnt from AnswerReaction r "
        + "where r.answer.id in :answerIds and " + EMOJI + " group by r.answer.id, r.emojiCode")
    List<CountRow> countByAnswers(@Param("answerIds") Collection<UUID> answerIds);

    @Query("select r.answer.id as answerId, r.emojiCode as emojiCode from AnswerReaction r "
        + "where r.answer.id in :answerIds and r.user.id = :userId and " + EMOJI)
    List<MineRow> findMine(@Param("answerIds") Collection<UUID> answerIds, @Param("userId") UUID userId);

    @EntityGraph(attributePaths = "user")
    @Query("select r from AnswerReaction r where r.answer.id = :answerId and " + EMOJI
        + " order by r.createdAt asc, r.id asc")
    List<AnswerReaction> findEmojiByAnswer(@Param("answerId") UUID answerId);
}
