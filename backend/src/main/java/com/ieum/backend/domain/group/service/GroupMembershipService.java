package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.config.GroupProperties;
import com.ieum.backend.domain.group.dto.GroupDtos.PendingRequestResponse;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupInviteRepository;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    /** 만료되지 않은 승인 대기 요청(오래된 순). */
    @Transactional(readOnly = true)
    public List<PendingRequestResponse> pendingRequests(UUID groupId, UUID ownerId) {
        requireOwner(groupId, ownerId);
        Instant now = Instant.now();
        return memberRepository.findAllByGroup_IdAndStatusOrderByRequestedAtAsc(groupId, MemberStatus.PENDING)
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
        if (memberRepository.countByGroup_IdAndStatus(groupId, MemberStatus.ACTIVE) >= properties.memberLimit()) {
            // 상한에 걸린 요청은 PENDING으로 남아 2일 만료까지 대기한다.
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }
        target.approve(now);
    }

    @Transactional
    public void reject(UUID groupId, UUID ownerId, UUID targetUserId) {
        lockGroup(groupId);
        requireOwner(groupId, ownerId);
        pendingOf(groupId, targetUserId).reject(Instant.now());
    }

    /** 강퇴(REMOVED): 코드로 재입장할 수 없다. 방장 본인은 {@link #leave}를 쓴다. */
    @Transactional
    public void remove(UUID groupId, UUID ownerId, UUID targetUserId) {
        lockGroup(groupId);
        requireOwner(groupId, ownerId);
        GroupMember target = memberRepository.findByGroup_IdAndUser_Id(groupId, targetUserId)
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

        List<GroupMember> candidates = memberRepository
            .findAllByGroup_IdAndStatusOrderByJoinedAtAscIdAsc(groupId, MemberStatus.ACTIVE).stream()
            .filter(m -> !m.getUser().getId().equals(userId))
            .toList();

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

    private void dissolve(UUID groupId, GroupMember lastOwner, Instant now) {
        lastOwner.changeOwner(false);
        lastOwner.leave(now);
        memberRepository.saveAndFlush(lastOwner);
        memberRepository.findAllByGroup_IdAndStatusOrderByRequestedAtAsc(groupId, MemberStatus.PENDING)
            .forEach(m -> m.reject(now));
        inviteRepository.deactivateActiveByGroupId(groupId);
        groupRepository.findById(groupId).ifPresent(FamilyGroup::archive);
    }

    private FamilyGroup lockGroup(UUID groupId) {
        return groupRepository.findByIdForUpdate(groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireOwner(UUID groupId, UUID userId) {
        if (!memberRepository.existsByGroup_IdAndUser_IdAndStatusAndOwnerTrue(groupId, userId, MemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_OWNER);
        }
    }

    private GroupMember pendingOf(UUID groupId, UUID targetUserId) {
        return memberRepository.findByGroup_IdAndUser_Id(groupId, targetUserId)
            .filter(m -> m.getStatus() == MemberStatus.PENDING)
            .orElseThrow(() -> new BusinessException(ErrorCode.JOIN_REQUEST_NOT_FOUND));
    }

    private boolean isExpired(GroupMember pending, Instant now) {
        return pending.getRequestedAt() == null
            || !pending.getRequestedAt().plus(properties.pendingTtl()).isAfter(now);
    }
}
