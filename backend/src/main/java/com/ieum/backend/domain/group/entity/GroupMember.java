package com.ieum.backend.domain.group.entity;

import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.user.entity.User;
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
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "group_members",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_group_member", columnNames = {"group_id", "user_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FamilyGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", length = 20, nullable = false)
    private MemberRole memberRole;

    @Column(name = "family_nickname", length = 40, nullable = false)
    private String familyNickname;

    @Column(name = "target_call_phone", length = 30)
    private String targetCallPhone;

    @Column(name = "custom_notification_time", nullable = false)
    private LocalTime customNotificationTime;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "is_owner", nullable = false)
    private boolean owner;

    @Column(name = "requested_at")
    private Instant requestedAt;

    /** 그룹 생성자: 방장이며 즉시 ACTIVE. */
    public static GroupMember createOwner(
        FamilyGroup group,
        User user,
        MemberRole memberRole,
        String familyNickname,
        Instant joinedAt
    ) {
        GroupMember member = create(group, user, memberRole, familyNickname, null, joinedAt);
        member.owner = true;
        return member;
    }

    /** 코드 입장 요청: 방장 승인 전까지 PENDING. */
    public static GroupMember createPending(
        FamilyGroup group,
        User user,
        MemberRole memberRole,
        String familyNickname,
        Instant requestedAt
    ) {
        GroupMember member = create(group, user, memberRole, familyNickname, null, requestedAt);
        member.status = MemberStatus.PENDING;
        member.requestedAt = requestedAt;
        return member;
    }

    /** 나갔던 멤버의 재요청: 같은 행을 이어 쓴다. */
    public void requestAgain(String familyNickname, Instant requestedAt) {
        this.status = MemberStatus.PENDING;
        this.familyNickname = familyNickname;
        this.requestedAt = requestedAt;
        this.leftAt = null;
    }

    /** PENDING이 만료됐을 때 새 요청으로 갱신한다(닉네임 유지). */
    public void renewRequest(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public static GroupMember create(
        FamilyGroup group,
        User user,
        MemberRole memberRole,
        String familyNickname,
        String targetCallPhone,
        Instant joinedAt
    ) {
        GroupMember member = new GroupMember();
        member.group = group;
        member.user = user;
        member.memberRole = memberRole;
        member.familyNickname = familyNickname;
        member.targetCallPhone = targetCallPhone;
        member.customNotificationTime = TimeZoneConstants.DAILY_QUESTION_ASSIGN_TIME_KST;
        member.notificationEnabled = true;
        member.status = MemberStatus.ACTIVE;
        member.joinedAt = joinedAt;
        return member;
    }

    public void updateNotificationPreference(LocalTime customNotificationTime, boolean notificationEnabled) {
        this.customNotificationTime = customNotificationTime;
        this.notificationEnabled = notificationEnabled;
    }

    public void leave(Instant leftAt) {
        this.status = MemberStatus.LEFT;
        this.leftAt = leftAt;
    }

    /** 방장 승인: PENDING → ACTIVE. 가입 일시는 승인 시점이다(방장 위임 순서 기준). */
    public void approve(Instant approvedAt) {
        this.status = MemberStatus.ACTIVE;
        this.joinedAt = approvedAt;
    }

    /** 거절은 LEFT로 돌려 보낸다. 거절된 사용자는 다시 요청할 수 있다. */
    public void reject(Instant at) {
        leave(at);
    }

    /** 강퇴: 코드로 재입장할 수 없다. */
    public void remove(Instant removedAt) {
        this.status = MemberStatus.REMOVED;
        this.leftAt = removedAt;
    }

    public void changeOwner(boolean owner) {
        this.owner = owner;
    }
}
