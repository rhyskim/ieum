package com.ieum.backend.domain.group.service;

import com.ieum.backend.domain.group.dto.GroupDtos.AliasResponse;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 호칭은 (group, viewer, target)마다 따로 저장한다. viewer는 항상 인증된 사용자이므로
 * 다른 사용자의 호칭은 조회·수정할 수 없다. 그룹 소속 확인은 컨트롤러의 {@code @GroupMemberOnly}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class MemberAliasService {

    private final JdbcTemplate jdbc;

    public Map<UUID, String> aliasesOf(UUID groupId, UUID viewerId) {
        Map<UUID, String> result = new HashMap<>();
        jdbc.query(
            "select target_id, alias from member_aliases where group_id = ? and viewer_id = ?",
            rs -> {
                result.put(rs.getObject("target_id", UUID.class), rs.getString("alias"));
            },
            groupId, viewerId);
        return result;
    }

    public List<AliasResponse> list(UUID groupId, UUID viewerId) {
        return aliasesOf(groupId, viewerId).entrySet().stream()
            .map(e -> new AliasResponse(e.getKey(), e.getValue()))
            .toList();
    }

    public AliasResponse set(UUID groupId, UUID viewerId, UUID targetId, String alias) {
        // 대상은 이 그룹에 속했던 사용자여야 한다(탈퇴·이탈한 작성자의 호칭도 허용).
        Integer exists = jdbc.queryForObject(
            "select count(*) from group_members where group_id = ? and user_id = ?",
            Integer.class, groupId, targetId);
        if (exists == null || exists == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String trimmed = alias.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        jdbc.update(
            """
            insert into member_aliases (group_id, viewer_id, target_id, alias)
            values (?, ?, ?, ?)
            on conflict (group_id, viewer_id, target_id)
            do update set alias = excluded.alias, updated_at = now()
            """,
            groupId, viewerId, targetId, trimmed);
        return new AliasResponse(targetId, trimmed);
    }

    public void delete(UUID groupId, UUID viewerId, UUID targetId) {
        jdbc.update(
            "delete from member_aliases where group_id = ? and viewer_id = ? and target_id = ?",
            groupId, viewerId, targetId);
    }
}
