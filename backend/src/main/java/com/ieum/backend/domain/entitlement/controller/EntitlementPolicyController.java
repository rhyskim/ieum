package com.ieum.backend.domain.entitlement.controller;

import com.ieum.backend.domain.common.enums.GroupPlanTier;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.entitlement.dto.FeatureEntitlementResponse;
import com.ieum.backend.domain.entitlement.service.FeatureGate;
import com.ieum.backend.global.common.ApiResponse;
import com.ieum.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
public class EntitlementPolicyController {

    private final FeatureGate featureGate;

    @GetMapping
    public ApiResponse<FeatureEntitlementResponse> getEntitlementPolicies(
        @AuthenticationPrincipal CurrentUser currentUser
    ) {
        MembershipTier userTier = currentUser != null ? currentUser.membershipTier() : MembershipTier.FREE;
        FeatureEntitlementResponse response = featureGate.getEntitlementPolicy(userTier, GroupPlanTier.FREE);
        return ApiResponse.ok(response);
    }
}
