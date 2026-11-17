package com.ieum.backend.domain.question.entity;

import com.ieum.backend.domain.common.enums.QuestionCategory;
import com.ieum.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "questions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "sequence_order", nullable = false, unique = true)
    private int sequenceOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, nullable = false)
    private QuestionCategory category;

    @Column(name = "question_text", columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Column(name = "tts_guide_text", columnDefinition = "TEXT")
    private String ttsGuideText;

    @Column(name = "follow_up_hint", columnDefinition = "TEXT")
    private String followUpHint;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public static Question create(
        int sequenceOrder,
        QuestionCategory category,
        String questionText,
        String ttsGuideText,
        String followUpHint
    ) {
        Question question = new Question();
        question.sequenceOrder = sequenceOrder;
        question.category = category;
        question.questionText = questionText;
        question.ttsGuideText = ttsGuideText;
        question.followUpHint = followUpHint;
        question.active = true;
        return question;
    }
}
