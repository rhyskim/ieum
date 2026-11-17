package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.config.GroupProperties;
import com.ieum.backend.domain.group.dto.GroupDtos.InviteCodeResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.JoinResponse;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.entity.GroupInvite;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.notify.GroupNotifier;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupInviteRepository;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupInviteService {

    private static final int MAX_ISSUE_ATTEMPTS = 5;

    private final GroupInviteRepository inviteRepository;
    private final GroupMemberRepository memberRepository;
    private final FamilyGroupRepository groupRepository;
    private final UserRepository userRepository;
    private final GroupService groupService;
    private final GroupProperties properties;
    private final GroupNotifier notifier;
    private final JoinAttemptGuard joinAttemptGuard;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    /**
     * 방장이 코드를 (재)발급한다. 이전 활성 코드는 폐기된다. 코드 중복(활성 코드끼리 유니크)이나
     * 동시 재발급으로 유니크 위반이 나면 새 트랜잭션에서 다시 시도한다.
     */
    public InviteCodeResponse issue(UUID groupId, UUID userId) {
        groupService.requireOwner(groupId, userId);
        for (int attempt = 1; attempt <= MAX_ISSUE_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    FamilyGroup group = groupRepository.getReferenceById(groupId);
                    User inviter = userRepository.getReferenceById(userId);
                    inviteRepository.deactivateActiveByGroupId(groupId);
                    GroupInvite invite = inviteRepository.saveAndFlush(GroupInvite.create(
                        group, inviter, newCode(), Instant.now().plus(properties.inviteTtl())));
                    return new InviteCodeResponse(invite.getInviteCode(), invite.getExpiresAt());
                });
            } catch (DataIntegrityViolationException e) {
                log.warn("초대 코드 발급 유니크 위반, 재시도 {}/{}", attempt, MAX_ISSUE_ATTEMPTS);
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    /** 현재 유효한 코드(없거나 만료면 empty). */
    public Optional<InviteCodeResponse> current(UUID groupId, UUID userId) {
        groupService.requireOwner(groupId, userId);
        return inviteRepository.findByGroup_IdAndActiveTrue(groupId)
            .filter(i -> i.getExpiresAt().isAfter(Instant.now()))
            .map(i -> new InviteCodeResponse(i.getInviteCode(), i.getExpiresAt()));
    }

    /**
     * 코드로 입장을 요청한다. 이미 PENDING/ACTIVE면 성공으로 응답(멱등)하고,
     * 응답에는 그룹 정보를 싣지 않는다.
     */
    public JoinResponse join(UUID userId, String code, String nickname) {
        // 잠금 확인 → 코드 검증 → 실패 기록 순서는 JoinAttemptGuard가 보장한다.
        UUID groupId = joinAttemptGuard.attempt(userId, () -> {
            GroupInvite invite = inviteRepository.findByInviteCodeAndActiveTrue(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_CODE_NOT_FOUND));
            if (!invite.getExpiresAt().isAfter(Instant.now())) {
                throw new BusinessException(ErrorCode.INVITE_CODE_EXPIRED);
            }
            return invite.getGroup().getId();
        });

        JoinOutcome outcome;
        try {
            outcome = tx.execute(status -> applyJoin(groupId, userId, nickname));
        } catch (DataIntegrityViolationException e) {
            // 같은 사용자의 동시 요청이 먼저 행을 만들었다: 행이 하나뿐임을 확인하고 성공으로 응답한다.
            outcome = tx.execute(status -> applyJoin(groupId, userId, nickname));
        }

        if (outcome.newlyRequested()) {
            memberRepository.findFirstByGroup_IdAndStatusAndOwnerTrue(groupId, MemberStatus.ACTIVE)
                .ifPresent(owner -> notifier.joinRequested(groupId, owner.getUser().getId(), userId));
        }
        return new JoinResponse(outcome.status());
    }

    private JoinOutcome applyJoin(UUID groupId, UUID userId, String nickname) {
        Instant now = Instant.now();
        Optional<GroupMember> existing = memberRepository.findByGroup_IdAndUser_Id(groupId, userId);
        if (existing.isEmpty()) {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            FamilyGroup group = groupRepository.getReferenceById(groupId);
            memberRepository.saveAndFlush(GroupMember.createPending(
                group, user, user.getDefaultRole(), GroupService.resolveNickname(nickname, user), now));
            return new JoinOutcome(MemberStatus.PENDING, true);
        }
        GroupMember member = existing.get();
        switch (member.getStatus()) {
            case ACTIVE:
                return new JoinOutcome(MemberStatus.ACTIVE, false);
            case PENDING:
                if (member.getRequestedAt() == null
                    || !member.getRequestedAt().plus(properties.pendingTtl()).isAfter(now)) {
                    member.renewRequest(now);
                    return new JoinOutcome(MemberStatus.PENDING, true);
                }
                return new JoinOutcome(MemberStatus.PENDING, false);
            case LEFT:
                User user = member.getUser();
                member.requestAgain(GroupService.resolveNickname(nickname, user), now);
                return new JoinOutcome(MemberStatus.PENDING, true);
            default:
                throw new BusinessException(ErrorCode.JOIN_NOT_ALLOWED);
        }
    }

    private String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private record JoinOutcome(MemberStatus status, boolean newlyRequested) {
    }
}
