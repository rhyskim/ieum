package com.ieum.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
    String endpoint,
    String publicEndpoint,
    String region,
    String accessKey,
    String secretKey,
    String bucketName,
    String ephemeralPrefix,
    String permanentPrefix,
    boolean pathStyleAccessEnabled,
    boolean useStaticCredentials
) {
}
