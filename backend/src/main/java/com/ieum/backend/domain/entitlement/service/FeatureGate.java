package com.ieum.backend.domain.entitlement.service;

import com.ieum.backend.domain.common.enums.GroupPlanTier;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.entitlement.dto.FeatureEntitlementResponse;

public interface FeatureGate {

    String resolveEffectivePlan(MembershipTier userTier, GroupPlanTier groupTier);

    FeatureEntitlementResponse getEntitlementPolicy(MembershipTier userTier, GroupPlanTier groupTier);

    void assertCanAttachOriginalVoice(MembershipTier userTier, GroupPlanTier groupTier);

    int maxVoiceDurationSeconds(MembershipTier userTier, GroupPlanTier groupTier);

    long maxAudioFileSizeBytes(MembershipTier userTier, GroupPlanTier groupTier);

    void validateVoiceUpload(int durationSec, long fileSizeBytes, MembershipTier userTier, GroupPlanTier groupTier);
}
