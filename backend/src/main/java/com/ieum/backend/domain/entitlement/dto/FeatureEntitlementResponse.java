package com.ieum.backend.domain.entitlement.dto;

public record FeatureEntitlementResponse(
    String currentPlan,
    boolean canAttachOriginalVoice,
    int maxVoiceDurationSeconds,
    int voiceServerRetentionDays,
    boolean canDownloadVoiceToLocal,
    String sttEngineType
) {
}
