package com.ieum.backend.domain.auth.social;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ieum.auth.social")
public record SocialAuthProperties(
    String kakaoAppId,
    String appleClientId,
    Duration timeout
) {
    public SocialAuthProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(3);
        }
    }
}
