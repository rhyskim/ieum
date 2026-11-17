package com.ieum.backend.domain.answer.entity;

import com.ieum.backend.domain.common.enums.EmojiCode;
import com.ieum.backend.domain.common.enums.ReactionType;
import com.ieum.backend.domain.common.enums.ReactionVoiceStatus;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "answer_reactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnswerReaction extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false)
    private Answer answer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 20, nullable = false)
    private ReactionType reactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "emoji_code", length = 30)
    private EmojiCode emojiCode;

    @Column(name = "voice_object_key", length = 512)
    private String voiceObjectKey;

    @Column(name = "voice_duration_sec")
    private Integer voiceDurationSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "voice_status", length = 30, nullable = false)
    private ReactionVoiceStatus voiceStatus;

    @Column(name = "voice_expires_at")
    private Instant voiceExpiresAt;

    public static AnswerReaction createEmojiReaction(Answer answer, User user, EmojiCode emojiCode) {
        AnswerReaction reaction = new AnswerReaction();
        reaction.answer = answer;
        reaction.user = user;
        reaction.reactionType = ReactionType.EMOJI;
        reaction.emojiCode = emojiCode;
        reaction.voiceStatus = ReactionVoiceStatus.NONE;
        return reaction;
    }

    public static AnswerReaction createVoiceReaction(
        Answer answer,
        User user,
        String voiceObjectKey,
        int voiceDurationSec,
        ReactionVoiceStatus voiceStatus,
        Instant voiceExpiresAt
    ) {
        AnswerReaction reaction = new AnswerReaction();
        reaction.answer = answer;
        reaction.user = user;
        reaction.reactionType = ReactionType.VOICE_MESSAGE;
        reaction.voiceObjectKey = voiceObjectKey;
        reaction.voiceDurationSec = voiceDurationSec;
        reaction.voiceStatus = voiceStatus;
        reaction.voiceExpiresAt = voiceExpiresAt;
        return reaction;
    }
}
