package com.ieum.backend.domain.group.entity;

import com.ieum.backend.domain.common.enums.GroupPlanTier;
import com.ieum.backend.domain.common.enums.GroupStatus;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.global.common.BaseTimeEntity;
import com.ieum.backend.global.common.TimeZoneConstants;
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
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "family_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyGroup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", length = 30, nullable = false)
    private GroupPlanTier planTier;

    @Column(name = "question_assign_time", nullable = false)
    private LocalTime questionAssignTime;

    @Column(name = "current_question_seq", nullable = false)
    private int currentQuestionSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private GroupStatus status;

    public static FamilyGroup create(String name, User creator) {
        FamilyGroup group = new FamilyGroup();
        group.name = name;
        group.creator = creator;
        group.planTier = GroupPlanTier.FREE;
        group.questionAssignTime = TimeZoneConstants.DAILY_QUESTION_ASSIGN_TIME_KST;
        group.currentQuestionSeq = 0;
        group.status = GroupStatus.ACTIVE;
        return group;
    }

    /** 혼자 남은 방장이 나가면 그룹을 해산한다. */
    public void archive() {
        this.status = GroupStatus.ARCHIVED;
    }

    public int advanceQuestionSequence() {
        this.currentQuestionSeq += 1;
        return this.currentQuestionSeq;
    }
}
