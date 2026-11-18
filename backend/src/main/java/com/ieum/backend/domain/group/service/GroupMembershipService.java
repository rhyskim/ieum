package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.common.enums.GroupStatus;
import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.config.GroupProperties;
import com.ieum.backend.domain.group.dto.GroupDtos.PendingRequestResponse;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupInviteRepository;
import com.ieum.backend.domain.group.notify.GroupNotifier;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인·거절·강퇴·이탈. 멤버 수나 방장이 바뀌는 모든 쓰기는 family_groups 행을 FOR UPDATE로 잠근 뒤
 * 처리하므로 서로 줄을 선다(ADR-0003 결정 9). 인원 수 컬럼은 두지 않고 ACTIVE 행을 센다.
 */
@Service
@RequiredArgsConstructor
public class GroupMembershipService {

    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupInviteRepository inviteRepository;
    private final GroupProperties properties;
    private final GroupNotifier notifier;

    /** 만료되지 않은 승인 대기 요청(오래된 순). */
    @Transactional(readOnly = true)
    public List<PendingRequestResponse> pendingRequests(UUID groupId, UUID ownerId) {
        requireOwner(groupId, ownerId);
        Instant now = Instant.now();
        return memberRepository.findValidPending(groupId)
            .stream()
            .filter(m -> !isExpired(m, now))
            .map(m -> new PendingRequestResponse(
                m.getUser().getId(),
                m.getFamilyNickname(),
                m.getRequestedAt(),
                m.getRequestedAt().plus(properties.pendingTtl())))
            .toList();
    }

    @Transactional
    public void approve(UUID groupId, UUID ownerId, UUID targetUserId) {
        lockGroup(groupId);
        requireOwner(groupId, ownerId);
        GroupMember target = pendingOf(groupId, targetUserId);
        Instant now = Instant.now();
        if (isExpired(target, now)) {
            throw new BusinessException(ErrorCode.JOIN_REQUEST_EXPIRED);
        }
        if (memberRepository.countValidActive(groupId) >= properties.memberLimit()) {
            // 상한에 걸린 요청은 PENDING으로 남아 2일 만료까지 대기한다.
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }
        target.approve(now);
        notifier.joinApproved(groupId, targetUserId);
    }

    @Transactional
    public void reject(UUID groupId, UUID ownerId, UUID targetUserId) {
        lockGroup(groupId);
        requireOwner(groupId, ownerId);
        pendingOf(groupId, targetUserId).reject(Instant.now());
        notifier.joinRejected(groupId, targetUserId);
    }

    /** 강퇴(REMOVED): 코드로 재입장할 수 없다. 방장 본인은 {@link #leave}를 쓴다. */
    @Transactional
    public void remove(UUID groupId, UUID ownerId, UUID targetUserId) {
        lockGroup(groupId);
        requireOwner(groupId, ownerId);
        GroupMember target = memberRepository.findValid(groupId, targetUserId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (target.isOwner()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        target.remove(Instant.now());
    }

    /**
     * 그룹 이탈. 방장이면 지정한 멤버(없으면 가장 오래된 ACTIVE: 가입 일시 → id)에게 방장을 넘기고,
     * 혼자면 그룹을 해산한다. 탈퇴 흐름(account)이 그대로 재사용할 수 있도록 HTTP와 분리했다.
     */
    @Transactional
    public void leave(UUID groupId, UUID userId, UUID successorUserId) {
        lockGroup(groupId);
        GroupMember me = memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_ACCESS_DENIED));
        Instant now = Instant.now();

        if (!me.isOwner()) {
            me.leave(now);
            return;
        }

        List<GroupMember> candidates = ownerCandidates(groupId, userId);

        if (candidates.isEmpty()) {
            dissolve(groupId, me, now);
            return;
        }
        GroupMember successor = successorUserId == null
            ? candidates.get(0)
            : candidates.stream()
                .filter(m -> m.getUser().getId().equals(successorUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));

        // 부분 유니크 인덱스(그룹당 ACTIVE 방장 1명): 이전 방장을 먼저 내리고 flush한 뒤 승계시킨다.
        me.changeOwner(false);
        me.leave(now);
        memberRepository.saveAndFlush(me);
        successor.changeOwner(true);
    }

    /**
     * 탈퇴 요청 시 방장 위임(ADR-0003 규칙: 가장 오래된 유효 ACTIVE 멤버, 가입 일시 → id). 호출자의 트랜잭션 안에서,
     * 호출자가 이미 users 행을 잠그고 deleted_at을 반영(flush)한 뒤 부른다. 내가 방장인 그룹을 id 오름차순으로
     * 잠가 교착을 피한다. {@link #leave}와 달리 내 멤버십은 ACTIVE로 두고(복구 대비), 후보가 없는 그룹은
     * 해산하지 않고 그대로 둔다(파기 시점에 해산, PR 2). 복구해도 방장은 돌아오지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delegateOwnershipsOnWithdrawal(UUID userId) {
        for (UUID groupId : memberRepository.findMyOwnedActiveGroupIds(userId)) {
            lockGroup(groupId);
            // 잠근 뒤에 멤버십을 새로 읽는다: 잠금 전에 다른 트랜잭션이 방장을 바꿨을 수 있다.
            GroupMember me = memberRepository.findByGroup_IdAndUser_Id(groupId, userId).orElse(null);
            if (me == null || me.getStatus() != MemberStatus.ACTIVE || !me.isOwner()) {
                continue;
            }
            List<GroupMember> candidates = ownerCandidates(groupId, userId);
            if (candidates.isEmpty()) {
                continue;
            }
            // 부분 유니크 인덱스(그룹당 ACTIVE 방장 1명): 이전 방장을 먼저 내리고 flush한 뒤 승계시킨다.
            me.changeOwner(false);
            memberRepository.saveAndFlush(me);
            candidates.get(0).changeOwner(true);
        }
    }

    /**
     * 복구 시 인원 상한 충돌 처리(ADR-0002 결정 12). 호출자가 users 복구 UPDATE를 먼저 반영한 같은 트랜잭션에서 부른다.
     * 내 ACTIVE 그룹을 id 오름차순으로 잠그고(승인과 같은 그룹 행 잠금), 본인을 포함한 유효 ACTIVE 수가
     * 상한을 넘으면 그 그룹의 내 멤버십을 LEFT로 바꾼다(다시 입장 요청 필요). 나머지 그룹은 그대로 복구된다.
     * 유예 중에 해산(ARCHIVED)된 그룹(유효 멤버가 방장뿐일 때 방장이 나간 경우)으로도 돌아가지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileOnRestore(UUID userId) {
        Instant now = Instant.now();
        for (UUID groupId : memberRepository.findMyActiveGroupIds(userId)) {
            FamilyGroup group = lockGroup(groupId);
            memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .ifPresent(me -> {
                    if (group.getStatus() != GroupStatus.ACTIVE
                        || memberRepository.countValidActive(groupId) > properties.memberLimit()) {
                        me.changeOwner(false);
                        me.leave(now);
                    }
                });
        }
    }

    /**
     * 파기 시점에 혼자 남은 그룹을 해산한다(ADR-0002 결정 8). 내 ACTIVE 그룹을 id 오름차순으로 잠그고(복구·탈퇴와 같은 락 순서),
     * 그룹이 ACTIVE이면서 본인을 뺀 유효 ACTIVE 멤버가 한 명도 없을 때만 {@link #dissolve}한다. 호출 시점에 본인은 이미
     * deleted_at이 있어 유효 멤버로 세어지지 않는다. 다른 유효 멤버가 있는 그룹, 이미 해산된 그룹은 건드리지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dissolveGroupsLeftEmptyByPurge(UUID userId) {
        Instant now = Instant.now();
        for (UUID groupId : memberRepository.findMyActiveGroupIds(userId)) {
            FamilyGroup group = lockGroup(groupId);
            if (group.getStatus() != GroupStatus.ACTIVE || memberRepository.countValidActive(groupId) > 0) {
                continue;
            }
            memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .ifPresent(me -> dissolve(groupId, me, now));
        }
    }

    /** 방장 후보: 본인을 뺀 유효 ACTIVE 멤버(가입 일시 → id 순). */
    private List<GroupMember> ownerCandidates(UUID groupId, UUID excludedUserId) {
        return memberRepository.findValidActive(groupId).stream()
            .filter(m -> !m.getUser().getId().equals(excludedUserId))
            .toList();
    }

    private void dissolve(UUID groupId, GroupMember lastOwner, Instant now) {
        lastOwner.changeOwner(false);
        lastOwner.leave(now);
        memberRepository.saveAndFlush(lastOwner);
        memberRepository.findValidPending(groupId).forEach(m -> m.reject(now));
        inviteRepository.deactivateActiveByGroupId(groupId);
        groupRepository.findById(groupId).ifPresent(FamilyGroup::archive);
    }

    private FamilyGroup lockGroup(UUID groupId) {
        return groupRepository.findByIdForUpdate(groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireOwner(UUID groupId, UUID userId) {
        if (!memberRepository.existsValidActiveOwner(groupId, userId)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_OWNER);
        }
    }

    private GroupMember pendingOf(UUID groupId, UUID targetUserId) {
        return memberRepository.findValid(groupId, targetUserId)
            .filter(m -> m.getStatus() == MemberStatus.PENDING)
            .orElseThrow(() -> new BusinessException(ErrorCode.JOIN_REQUEST_NOT_FOUND));
    }

    private boolean isExpired(GroupMember pending, Instant now) {
        return pending.getRequestedAt() == null
            || !pending.getRequestedAt().plus(properties.pendingTtl()).isAfter(now);
    }
}
