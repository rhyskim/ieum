package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.common.enums.MemberStatus;
import com.ieum.backend.domain.group.dto.GroupDtos.CreateGroupRequest;
import com.ieum.backend.domain.group.dto.GroupDtos.GroupDetailResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.GroupSummaryResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.MemberResponse;
import com.ieum.backend.domain.group.entity.FamilyGroup;
import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.FamilyGroupRepository;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.user.entity.User;
import com.ieum.backend.domain.user.repository.UserRepository;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MemberAliasService aliasService;

    @Transactional
    public GroupSummaryResponse create(UUID userId, CreateGroupRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        FamilyGroup group = groupRepository.save(FamilyGroup.create(request.name().trim(), user));
        memberRepository.save(GroupMember.createOwner(
            group, user, user.getDefaultRole(), resolveNickname(request.nickname(), user), Instant.now()));
        return new GroupSummaryResponse(group.getId(), group.getName(), true);
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryResponse> myGroups(UUID userId) {
        return memberRepository.findAllByUser_IdAndStatusOrderByJoinedAtAsc(userId, MemberStatus.ACTIVE).stream()
            .map(m -> new GroupSummaryResponse(m.getGroup().getId(), m.getGroup().getName(), m.isOwner()))
            .toList();
    }

    /** 호출 전에 {@code @GroupMemberOnly}로 ACTIVE 멤버임이 검증된다. */
    @Transactional(readOnly = true)
    public GroupDetailResponse detail(UUID groupId, UUID viewerId) {
        FamilyGroup group = groupRepository.findById(groupId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Map<UUID, String> aliases = aliasService.aliasesOf(groupId, viewerId);
        List<MemberResponse> members = memberRepository
            .findAllByGroup_IdAndStatusOrderByJoinedAtAscIdAsc(groupId, MemberStatus.ACTIVE).stream()
            .map(m -> {
                UUID targetId = m.getUser().getId();
                String alias = aliases.get(targetId);
                return new MemberResponse(
                    targetId,
                    alias != null ? alias : m.getFamilyNickname(),
                    alias,
                    m.getFamilyNickname(),
                    m.getMemberRole(),
                    m.isOwner(),
                    targetId.equals(viewerId),
                    m.getJoinedAt());
            })
            .toList();
        return new GroupDetailResponse(group.getId(), group.getName(), members);
    }

    public void requireOwner(UUID groupId, UUID userId) {
        if (!memberRepository.existsByGroup_IdAndUser_IdAndStatusAndOwnerTrue(groupId, userId, MemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_OWNER);
        }
    }

    static String resolveNickname(String requested, User user) {
        return requested == null || requested.isBlank() ? user.getName() : requested.trim();
    }
}
