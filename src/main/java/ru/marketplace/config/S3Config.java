package ru.marketplace.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Value("${s3.access-key-id}")
    private String accessKey;

    @Value("${s3.secret-access-key}")
    private String accessSecretKey;

    @Value("${s3.region}")
    private String region;

    @Value("${s3.endpoint}")
    private String endpointApi;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, accessSecretKey);
        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointApi))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(false)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner createS3Presigner() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, accessSecretKey);
        return S3Presigner.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointApi))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }
}
