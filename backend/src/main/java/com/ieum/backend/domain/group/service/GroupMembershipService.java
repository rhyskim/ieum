package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.answer.service.RevealReevaluator;
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
    private final RevealReevaluator revealReevaluator;

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
        // 미답변자가 빠져 "전원 답변"이 성립할 수 있다(ADR-0007 결정 8). 락 순서: group(위에서 잡음) → daily_questions.
        revealReevaluator.reevaluateGroup(groupId);
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
            revealReevaluator.reevaluateGroup(groupId); // 락 순서: group(위에서 잡음) → daily_questions
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
        revealReevaluator.reevaluateGroup(groupId); // 방장이 미답변자였다면 전원 답변이 성립할 수 있다
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
     * 탈퇴 요청 후 내 ACTIVE 그룹들의 LOCKED 질문을 재평가한다(ADR-0007 결정 8). 호출자(AccountService.withdraw)가
     * users 행 FOR UPDATE와 deleted_at flush, {@link #delegateOwnershipsOnWithdrawal}을 마친 뒤 마지막에 부른다.
     * <p>
     * <b>여기서 family_groups 락을 새로 잡지 않는다(의도적).</b> 위임 단계가 "내가 방장인 그룹"만 id 오름차순으로 이미
     * 잠갔는데, 여기서 내 모든 그룹을 잠그면 큰 id의 방장 그룹을 잡은 뒤 작은 id의 일반 그룹을 잡게 되어
     * 전역 락 순서(users → family_groups(id 오름차순) → daily_questions)가 깨지고, 같은 그룹을 오름차순으로 잠그는
     * 다른 withdraw/leave와 교착이 난다. daily_questions 행만 id 오름차순으로 잠가도 되는 이유: 저장(group S → dq X)과
     * leave/remove(group X → dq X)는 우리가 쥔 users·group 락을 기다리지 않으므로 대기 사이클이 생기지 않는다.
     * 나중에 누가 여기에 group 락을 추가하면 이 보장이 사라진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reevaluateRevealsOnWithdrawal(UUID userId) {
        for (UUID groupId : memberRepository.findMyActiveGroupIds(userId)) {
            revealReevaluator.reevaluateGroup(groupId);
        }
    }

    /**
     * 복구 시 인원 상한 충돌 처리(ADR-0002 결정 12). 호출자가 users 복구 UPDATE를 먼저 반영한 같은 트랜잭션에서 부른다.
     * 내 ACTIVE 그룹을 id 오름차순으로 잠그고(승인과 같은 그룹 행 잠금), 본인을 포함한 유효 ACTIVE 수가
     * 상한을 넘으면 그 그룹의 내 멤버십을 LEFT로 바꾼다(다시 입장 요청 필요). 나머지 그룹은 그대로 복구된다.
     * 유예 중에 해산(ARCHIVED)된 그룹(유효 멤버가 방장뿐일 때 방장이 나간 경우)으로도 돌아가지 않는다.
     * 복귀하는 그룹에 유효 방장이 없고 방장 행이 이미 파기됐다면 복구자가 방장이 된다(ADR-0002 결정 16, {@link #promoteIfOwnerPurged}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileOnRestore(UUID userId) {
        Instant now = Instant.now();
        for (UUID groupId : memberRepository.findMyActiveGroupIds(userId)) {
            FamilyGroup group = lockGroup(groupId);
            GroupMember me = memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElse(null);
            if (me == null) {
                continue;
            }
            if (group.getStatus() != GroupStatus.ACTIVE
                || memberRepository.countValidActive(groupId) > properties.memberLimit()) {
                me.changeOwner(false);
                me.leave(now);
                continue;
            }
            promoteIfOwnerPurged(groupId, userId);
        }
    }

    /**
     * 복구한 사용자가 돌아온 그룹에 유효 방장이 없고, 비어 있는 방장 자리가 "파기된 방장 행"이라면 복구자가 방장이 된다
     * (ADR-0002 결정 16). 방장이 파기된 시점에 유효 멤버가 없어 위임되지 못한 그룹이 방장 없이 남는 것을 막는다.
     * 방장이 단지 탈퇴 유예 중(복구 가능)인 경우는 대상이 아니다: 그 사람의 복귀 권리를 존중하고, 그가 복귀하지 않고
     * 파기되면 결정 15의 위임(유효 멤버가 있으면 가장 오래된 유효 멤버)으로 이어지기 때문이다.
     * 부분 유니크 인덱스(그룹당 ACTIVE 방장 1명) 때문에 기존(파기된) 방장 해제 → flush → 승격 순서로 처리한다.
     */
    private void promoteIfOwnerPurged(UUID groupId, UUID userId) {
        if (memberRepository.findValidActiveOwner(groupId).isPresent()) {
            return;
        }
        // 벌크 UPDATE가 영속성 컨텍스트를 비우므로(flush 후 clear) 승격할 멤버십은 그 뒤에 새로 읽는다.
        if (memberRepository.releasePurgedOwners(groupId) == 0) {
            return;
        }
        memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .ifPresent(m -> m.changeOwner(true));
    }

    /**
     * 파기 시점의 그룹 정리(ADR-0002 결정 8·15). 내 ACTIVE 그룹을 id 오름차순으로 잠그고(이미 해산된 그룹은 건드리지 않는다):
     * <ol>
     *   <li>내가 방장이고 그룹에 유효 멤버가 있으면 가장 오래된 유효 멤버(가입 일시 → id)에게 방장을 위임한다
     *       (후보 선정은 탈퇴 요청과 같은 {@link #ownerCandidates}). 방장이 파기된 뒤에도 방장이 없는 그룹이 남지 않게 한다.
     *   <li>그렇지 않고 본인을 뺀 ACTIVE 멤버 중 아직 파기되지 않은 사람이 한 명도 없으면 {@link #dissolve}하고
     *       이미 파기된 방장 등 남은 ACTIVE 행도 LEFT·방장 해제로 함께 정리한다.
     * </ol>
     * 해산 판정을 "유효 멤버"가 아니라 "파기되지 않은 멤버"로 세는 이유: 복구할 수 있는 탈퇴 유예 중 멤버(예: 아직 유예 중인
     * 방장)가 남아 있는 그룹을, 먼저 31일이 지난 다른 사람의 파기 때문에 미리 해산하면 안 되기 때문이다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleGroupsOnPurge(UUID userId) {
        Instant now = Instant.now();
        for (UUID groupId : memberRepository.findMyActiveGroupIds(userId)) {
            FamilyGroup group = lockGroup(groupId);
            if (group.getStatus() != GroupStatus.ACTIVE) {
                continue;
            }
            // 잠근 뒤에 멤버십을 새로 읽는다.
            GroupMember me = memberRepository.findByGroup_IdAndUser_Id(groupId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElse(null);
            if (me == null) {
                continue;
            }
            if (me.isOwner()) {
                List<GroupMember> candidates = ownerCandidates(groupId, userId);
                if (!candidates.isEmpty()) {
                    // 부분 유니크 인덱스(그룹당 ACTIVE 방장 1명): 이전 방장을 먼저 내리고 flush한 뒤 승계시킨다.
                    me.changeOwner(false);
                    memberRepository.saveAndFlush(me);
                    candidates.get(0).changeOwner(true);
                    continue;
                }
            }
            if (memberRepository.countActiveNotPurgedExcept(groupId, userId) == 0) {
                dissolve(groupId, me, now);
                memberRepository.leaveAllActive(groupId, now);
            }
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
