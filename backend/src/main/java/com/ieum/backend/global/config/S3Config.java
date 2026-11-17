package com.ieum.backend.global.config;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final S3Properties s3Properties;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(s3Properties.region()))
            .credentialsProvider(resolveCredentialsProvider())
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(s3Properties.pathStyleAccessEnabled())
                .build());

        if (s3Properties.endpoint() != null && !s3Properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3Properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
            .region(Region.of(s3Properties.region()))
            .credentialsProvider(resolveCredentialsProvider())
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(s3Properties.pathStyleAccessEnabled())
                .build());

        String presignEndpoint = s3Properties.publicEndpoint() != null && !s3Properties.publicEndpoint().isBlank()
            ? s3Properties.publicEndpoint()
            : s3Properties.endpoint();
        if (presignEndpoint != null && !presignEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(presignEndpoint));
        }
        return builder.build();
    }

    private AwsCredentialsProvider resolveCredentialsProvider() {
        if (s3Properties.useStaticCredentials()) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3Properties.accessKey(), s3Properties.secretKey())
            );
        }
        return DefaultCredentialsProvider.create();
    }
}
