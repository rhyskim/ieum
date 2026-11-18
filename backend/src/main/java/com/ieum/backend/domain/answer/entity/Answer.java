package com.ieum.backend.domain.answer.entity;

import com.ieum.backend.domain.common.enums.AudioStatus;
import com.ieum.backend.domain.common.enums.InputMethod;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.question.entity.DailyQuestion;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.BaseTimeEntity;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "answers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_answer_per_user", columnNames = {"daily_question_id", "user_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Answer extends BaseTimeEntity {

    public static final String DEFAULT_AUDIO_CODEC = "aac_m4a_64k";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_question_id", nullable = false)
    private DailyQuestion dailyQuestion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FamilyGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "stt_raw_text", columnDefinition = "TEXT")
    private String sttRawText;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_method", length = 30, nullable = false)
    private InputMethod inputMethod;

    @Column(name = "audio_object_key", length = 512)
    private String audioObjectKey;

    @Column(name = "audio_duration_sec")
    private Integer audioDurationSec;

    @Column(name = "audio_size_bytes")
    private Long audioSizeBytes;

    @Column(name = "audio_codec", length = 30)
    private String audioCodec;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_status", length = 30, nullable = false)
    private AudioStatus audioStatus;

    @Column(name = "audio_expires_at")
    private Instant audioExpiresAt;

    @Column(name = "reached_max_duration", nullable = false)
    private boolean reachedMaxDuration;

    public static Answer createVoiceAnswer(
        DailyQuestion dailyQuestion,
        FamilyGroup group,
        User user,
        String textContent,
        String sttRawText,
        String audioObjectKey,
        int audioDurationSec,
        long audioSizeBytes,
        AudioStatus initialAudioStatus,
        Instant audioExpiresAt,
        boolean reachedMaxDuration
    ) {
        Answer answer = new Answer();
        answer.dailyQuestion = dailyQuestion;
        answer.group = group;
        answer.user = user;
        answer.textContent = textContent;
        answer.sttRawText = sttRawText;
        answer.inputMethod = InputMethod.VOICE_WITH_STT;
        answer.audioObjectKey = audioObjectKey;
        answer.audioDurationSec = audioDurationSec;
        answer.audioSizeBytes = audioSizeBytes;
        answer.audioCodec = DEFAULT_AUDIO_CODEC;
        answer.audioStatus = initialAudioStatus;
        answer.audioExpiresAt = audioExpiresAt;
        answer.reachedMaxDuration = reachedMaxDuration;
        return answer;
    }

    public static Answer createTextAnswer(
        DailyQuestion dailyQuestion,
        FamilyGroup group,
        User user,
        String textContent
    ) {
        Answer answer = new Answer();
        answer.dailyQuestion = dailyQuestion;
        answer.group = group;
        answer.user = user;
        answer.textContent = textContent;
        answer.sttRawText = null;
        answer.inputMethod = InputMethod.TEXT_ONLY;
        answer.audioObjectKey = null;
        answer.audioDurationSec = null;
        answer.audioSizeBytes = null;
        answer.audioCodec = null;
        answer.audioStatus = AudioStatus.NONE;
        answer.audioExpiresAt = null;
        answer.reachedMaxDuration = false;
        return answer;
    }

    /** 텍스트 답변 수정. 공개 상태는 건드리지 않는다(ADR-0007 결정 7). */
    public void editText(String textContent) {
        this.textContent = textContent;
    }

    /**
     * D-7 만료 사전 알림 발송 완료 시 ACTIVE -> EXPIRING_NOTIFIED 로 전환합니다.
     */
    public void markAudioExpiringNotified() {
        if (this.audioStatus != AudioStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_AUDIO_STATE_TRANSITION);
        }
        this.audioStatus = AudioStatus.EXPIRING_NOTIFIED;
    }

    /**
     * 30일 경과로 서버 원본 음성 만료 시 ACTIVE 또는 EXPIRING_NOTIFIED -> EXPIRED_DELETED 로 전환합니다.
     * CLAUDE.md §6-5 불변식: textContent 및 sttRawText 는 절대 수정하거나 삭제하지 않고 영구 보존합니다.
     */
    public void markAudioExpired() {
        if (this.audioStatus != AudioStatus.ACTIVE && this.audioStatus != AudioStatus.EXPIRING_NOTIFIED) {
            throw new BusinessException(ErrorCode.INVALID_AUDIO_STATE_TRANSITION);
        }
        this.audioStatus = AudioStatus.EXPIRED_DELETED;
    }
}
