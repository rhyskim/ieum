package com.ieum.backend.domain.question.entity;

import com.ieum.backend.domain.common.enums.RevealStatus;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.global.common.BaseCreatedEntity;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "daily_questions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_daily_question_group_date", columnNames = {"group_id", "cycle_date"}),
        @UniqueConstraint(name = "uq_daily_question_id_group", columnNames = {"id", "group_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyQuestion extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FamilyGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "cycle_date", nullable = false)
    private LocalDate cycleDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reveal_status", length = 30, nullable = false)
    private RevealStatus revealStatus;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "reminder_sent", nullable = false)
    private boolean reminderSent;

    @Column(name = "auto_reveal_at", nullable = false)
    private Instant autoRevealAt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    public static DailyQuestion create(FamilyGroup group, Question question, LocalDate cycleDate) {
        DailyQuestion dq = new DailyQuestion();
        dq.group = group;
        dq.question = question;
        dq.cycleDate = cycleDate;
        dq.revealStatus = RevealStatus.LOCKED;
        dq.assignedAt = TimeZoneConstants.assignedAt(cycleDate);
        dq.reminderSent = false;
        dq.autoRevealAt = TimeZoneConstants.autoRevealAt(cycleDate);
        dq.revealedAt = null;
        return dq;
    }

    /**
     * 그룹 활성 멤버 전원이 답변을 마쳤을 때 호출합니다.
     * - LOCKED 상태에서 전원 답변 시 REVEALED_ALL 로 전환하고 revealedAt = now 를 기록합니다.
     * - REVEALED_TIMEOUT 상태에서 지각 멤버가 마저 답변해 전원 답변이 되면 REVEALED_ALL 로 승격하되 최초 공개 시각(revealedAt)은 보존합니다.
     */
    public boolean checkAndRevealIfAllAnswered(int activeMemberCount, int answeredCount, Instant now) {
        if (activeMemberCount <= 0 || answeredCount < activeMemberCount) {
            return false;
        }
        if (this.revealStatus == RevealStatus.LOCKED) {
            this.revealStatus = RevealStatus.REVEALED_ALL;
            this.revealedAt = now;
            return true;
        }
        if (this.revealStatus == RevealStatus.REVEALED_TIMEOUT) {
            this.revealStatus = RevealStatus.REVEALED_ALL;
            return true;
        }
        return false;
    }

    /**
     * 익일 17:00 KST (autoRevealAt) 도래 시 최소 1명 이상 답변한 경우 REVEALED_TIMEOUT 으로 공개합니다.
     */
    public boolean revealOnTimeoutIfEligible(int answeredCount, Instant now) {
        if (this.revealStatus != RevealStatus.LOCKED) {
            return false;
        }
        if (now.isBefore(this.autoRevealAt) || answeredCount < 1) {
            return false;
        }
        this.revealStatus = RevealStatus.REVEALED_TIMEOUT;
        this.revealedAt = now;
        return true;
    }

    public void markReminderSent() {
        this.reminderSent = true;
    }
}
