package com.ieum.backend.domain.entitlement.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ieum.entitlement")
public record EntitlementPolicyProperties(
    String defaultPlan,
    Map<String, PlanPolicySpec> plans
) {
    public record PlanPolicySpec(
        boolean canAttachOriginalVoice,
        int maxVoiceDurationSeconds,
        long maxAudioSizeBytes,
        int voiceServerRetentionDays,
        boolean canDownloadVoiceToLocal,
        String sttEngineType
    ) {
    }
}
