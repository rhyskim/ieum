package com.ieum.backend.domain.group.dto;

import com.ieum.backend.domain.common.enums.MemberRole;
import com.ieum.backend.domain.common.enums.MemberStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroupRequest(
        @NotBlank(message = "그룹 이름을 입력해 주세요.") @Size(max = 100, message = "그룹 이름은 100자 이하로 입력해 주세요.") String name,
        @Size(max = 40, message = "호칭은 40자 이하로 입력해 주세요.") String nickname
    ) {
    }

    public record GroupSummaryResponse(UUID id, String name, boolean owner) {
    }

    public record MemberResponse(
        UUID userId,
        /** 내가 정한 호칭이 있으면 그것, 없으면 상대가 입장 때 정한 이름. */
        String displayName,
        String alias,
        String familyNickname,
        MemberRole memberRole,
        boolean owner,
        boolean me,
        Instant joinedAt
    ) {
    }

    public record GroupDetailResponse(UUID id, String name, List<MemberResponse> members) {
    }

    public record InviteCodeResponse(String code, Instant expiresAt) {
    }

    public record JoinRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "입장 코드는 숫자 6자리예요.") String code,
        @Size(max = 40, message = "호칭은 40자 이하로 입력해 주세요.") String nickname
    ) {
    }

    /** 입장 전에는 그룹 정보를 싣지 않는다(상태만). */
    public record JoinResponse(MemberStatus status) {
    }

    public record AliasRequest(
        @NotBlank(message = "호칭을 입력해 주세요.") @Size(max = 40, message = "호칭은 40자 이하로 입력해 주세요.") String alias
    ) {
    }

    public record AliasResponse(UUID targetUserId, String alias) {
    }

    public record SupportRequestBody(
        @NotBlank(message = "문의 내용을 입력해 주세요.") @Size(max = 2000, message = "문의 내용은 2000자 이하로 입력해 주세요.") String message
    ) {
    }
}
