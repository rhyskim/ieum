package com.ieum.backend.domain.group.controller;

import com.ieum.backend.domain.group.dto.GroupDtos.AliasRequest;
import com.ieum.backend.domain.group.dto.GroupDtos.AliasResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.CreateGroupRequest;
import com.ieum.backend.domain.group.dto.GroupDtos.GroupDetailResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.GroupSummaryResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.InviteCodeResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.JoinRequest;
import com.ieum.backend.domain.group.dto.GroupDtos.LeaveRequest;
import com.ieum.backend.domain.group.dto.GroupDtos.PendingRequestResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.JoinResponse;
import com.ieum.backend.domain.group.dto.GroupDtos.SupportRequestBody;
import com.ieum.backend.domain.group.service.GroupInviteService;
import com.ieum.backend.domain.group.service.GroupMembershipService;
import com.ieum.backend.domain.group.service.GroupService;
import com.ieum.backend.domain.group.service.MemberAliasService;
import com.ieum.backend.domain.group.service.SupportRequestService;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import com.ieum.backend.global.security.GroupMemberOnly;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final GroupInviteService inviteService;
    private final GroupMembershipService membershipService;
    private final MemberAliasService aliasService;
    private final SupportRequestService supportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GroupSummaryResponse> create(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody CreateGroupRequest request
    ) {
        return ApiResponse.ok(groupService.create(user.userId(), request));
    }

    @GetMapping
    public ApiResponse<List<GroupSummaryResponse>> myGroups(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(groupService.myGroups(user.userId()));
    }

    @GetMapping("/{groupId}")
    @GroupMemberOnly
    public ApiResponse<GroupDetailResponse> detail(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId
    ) {
        return ApiResponse.ok(groupService.detail(groupId, user.userId()));
    }

    /** 코드로 입장 요청. 그룹을 모르는 상태이므로 groupId 없이 코드만 받는다. */
    @PostMapping("/join")
    public ApiResponse<JoinResponse> join(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody JoinRequest request
    ) {
        return ApiResponse.ok(inviteService.join(user.userId(), request.code(), request.nickname()));
    }

    @PostMapping("/{groupId}/invite-code")
    @GroupMemberOnly
    public ApiResponse<InviteCodeResponse> issueInviteCode(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId
    ) {
        return ApiResponse.ok(inviteService.issue(groupId, user.userId()));
    }

    /** 현재 유효한 코드. 없거나 만료면 data가 null이다. */
    @GetMapping("/{groupId}/invite-code")
    @GroupMemberOnly
    public ApiResponse<InviteCodeResponse> currentInviteCode(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId
    ) {
        return ApiResponse.ok(inviteService.current(groupId, user.userId()).orElse(null));
    }

    @GetMapping("/{groupId}/aliases")
    @GroupMemberOnly
    public ApiResponse<List<AliasResponse>> aliases(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId
    ) {
        return ApiResponse.ok(aliasService.list(groupId, user.userId()));
    }

    @PutMapping("/{groupId}/aliases/{targetUserId}")
    @GroupMemberOnly
    public ApiResponse<AliasResponse> setAlias(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID targetUserId,
        @Valid @RequestBody AliasRequest request
    ) {
        return ApiResponse.ok(aliasService.set(groupId, user.userId(), targetUserId, request.alias()));
    }

    @DeleteMapping("/{groupId}/aliases/{targetUserId}")
    @GroupMemberOnly
    public ApiResponse<Void> deleteAlias(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID targetUserId
    ) {
        aliasService.delete(groupId, user.userId(), targetUserId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{groupId}/support-requests")
    @GroupMemberOnly
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> submitSupportRequest(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @Valid @RequestBody SupportRequestBody request
    ) {
        groupService.requireOwner(groupId, user.userId());
        supportService.submit(groupId, user.userId(), request.message());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{groupId}/join-requests")
    @GroupMemberOnly
    public ApiResponse<List<PendingRequestResponse>> pendingRequests(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId
    ) {
        return ApiResponse.ok(membershipService.pendingRequests(groupId, user.userId()));
    }

    @PostMapping("/{groupId}/join-requests/{targetUserId}/approve")
    @GroupMemberOnly
    public ApiResponse<Void> approve(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID targetUserId
    ) {
        membershipService.approve(groupId, user.userId(), targetUserId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{groupId}/join-requests/{targetUserId}/reject")
    @GroupMemberOnly
    public ApiResponse<Void> reject(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID targetUserId
    ) {
        membershipService.reject(groupId, user.userId(), targetUserId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{groupId}/members/{targetUserId}")
    @GroupMemberOnly
    public ApiResponse<Void> removeMember(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @PathVariable UUID targetUserId
    ) {
        membershipService.remove(groupId, user.userId(), targetUserId);
        return ApiResponse.ok(null);
    }

    /** 그룹 이탈. 방장이면 body의 successorUserId(선택)에게 위임한다. */
    @PostMapping("/{groupId}/leave")
    @GroupMemberOnly
    public ApiResponse<Void> leave(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID groupId,
        @RequestBody(required = false) LeaveRequest request
    ) {
        membershipService.leave(groupId, user.userId(), request == null ? null : request.successorUserId());
        return ApiResponse.ok(null);
    }
}
