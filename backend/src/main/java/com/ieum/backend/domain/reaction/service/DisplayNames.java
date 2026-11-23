package com.ieum.backend.domain.reaction.service;

import com.ieum.backend.domain.group.entity.GroupMember;
import com.ieum.backend.domain.group.repository.GroupMemberRepository;
import com.ieum.backend.domain.group.service.MemberAliasService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 작성자·리액션한 사람의 표기 규칙(ADR-0002 결정 3, ADR-0008 결정 4): 보는 사람이 정한 호칭 → 그룹의 유효 멤버
 * 가족 호칭 → "탈퇴한 사용자". 탈퇴 유예·파기·이탈한 사람은 유효 멤버가 아니므로 마지막 규칙으로 떨어진다.
 */
@Component
@RequiredArgsConstructor
public class DisplayNames {

    public static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    private final GroupMemberRepository memberRepository;
    private final MemberAliasService aliasService;

    public Function<UUID, String> forViewer(UUID groupId, UUID viewerId) {
        Map<UUID, String> names = new HashMap<>();
        for (GroupMember m : memberRepository.findValidActive(groupId)) {
            names.put(m.getUser().getId(), m.getFamilyNickname());
        }
        Map<UUID, String> aliases = aliasService.aliasesOf(groupId, viewerId);
        return userId -> aliases.getOrDefault(userId, names.getOrDefault(userId, WITHDRAWN_NAME));
    }
}
