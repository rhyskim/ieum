package com.ieum.backend.domain.entitlement.service;

import com.ieum.backend.domain.common.enums.GroupPlanTier;
import com.ieum.backend.domain.common.enums.MembershipTier;
import com.ieum.backend.domain.entitlement.config.EntitlementPolicyProperties;
import com.ieum.backend.domain.entitlement.config.EntitlementPolicyProperties.PlanPolicySpec;
import com.ieum.backend.domain.entitlement.dto.FeatureEntitlementResponse;
import com.ieum.backend.global.exception.BusinessException;
import com.ieum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntitlementService implements FeatureGate {

    private static final String PLAN_PREMIUM = "PREMIUM";

    private final EntitlementPolicyProperties properties;

    @Override
    public String resolveEffectivePlan(MembershipTier userTier, GroupPlanTier groupTier) {
        if (userTier == MembershipTier.PREMIUM || groupTier == GroupPlanTier.PREMIUM_FAMILY) {
            return PLAN_PREMIUM;
        }
        return properties.defaultPlan() != null && !properties.defaultPlan().isBlank()
            ? properties.defaultPlan()
            : "FREE";
    }

    @Override
    public FeatureEntitlementResponse getEntitlementPolicy(MembershipTier userTier, GroupPlanTier groupTier) {
        String planKey = resolveEffectivePlan(userTier, groupTier);
        PlanPolicySpec spec = resolveSpec(planKey);
        return new FeatureEntitlementResponse(
            planKey,
            spec.canAttachOriginalVoice(),
            spec.maxVoiceDurationSeconds(),
            spec.voiceServerRetentionDays(),
            spec.canDownloadVoiceToLocal(),
            spec.sttEngineType()
        );
    }

    @Override
    public void assertCanAttachOriginalVoice(MembershipTier userTier, GroupPlanTier groupTier) {
        PlanPolicySpec spec = resolveSpec(resolveEffectivePlan(userTier, groupTier));
        if (!spec.canAttachOriginalVoice()) {
            throw new BusinessException(ErrorCode.FEATURE_NOT_ENTITLED);
        }
    }

    @Override
    public int maxVoiceDurationSeconds(MembershipTier userTier, GroupPlanTier groupTier) {
        return resolveSpec(resolveEffectivePlan(userTier, groupTier)).maxVoiceDurationSeconds();
    }

    @Override
    public long maxAudioFileSizeBytes(MembershipTier userTier, GroupPlanTier groupTier) {
        return resolveSpec(resolveEffectivePlan(userTier, groupTier)).maxAudioSizeBytes();
    }

    @Override
    public void validateVoiceUpload(
        int durationSec,
        long fileSizeBytes,
        MembershipTier userTier,
        GroupPlanTier groupTier
    ) {
        assertCanAttachOriginalVoice(userTier, groupTier);
        int maxDuration = maxVoiceDurationSeconds(userTier, groupTier);
        if (durationSec <= 0 || durationSec > maxDuration) {
            throw new BusinessException(ErrorCode.VOICE_DURATION_EXCEEDED);
        }
        long maxSizeBytes = maxAudioFileSizeBytes(userTier, groupTier);
        if (fileSizeBytes <= 0 || fileSizeBytes > maxSizeBytes) {
            throw new BusinessException(ErrorCode.VOICE_FILE_SIZE_EXCEEDED);
        }
    }

    private PlanPolicySpec resolveSpec(String planKey) {
        MapCheck:
        if (properties.plans() != null) {
            PlanPolicySpec spec = properties.plans().get(planKey);
            if (spec != null) {
                return spec;
            }
            PlanPolicySpec fallback = properties.plans().get(properties.defaultPlan());
            if (fallback != null) {
                return fallback;
            }
        }
        throw new IllegalStateException("Entitlement policy configuration missing for plan: " + planKey);
    }
}
