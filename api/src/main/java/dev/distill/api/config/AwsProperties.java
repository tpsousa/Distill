package dev.distill.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String region,
        String endpoint,
        String accessKeyId,
        String secretAccessKey,
        boolean s3PathStyleAccess) {

    public AwsProperties {
        region = requireText(region, "region");
        if (hasText(accessKeyId) != hasText(secretAccessKey)) {
            throw new IllegalArgumentException("accessKeyId and secretAccessKey must be provided together");
        }
    }

    public boolean hasEndpoint() {
        return hasText(endpoint);
    }

    public boolean hasStaticCredentials() {
        return hasText(accessKeyId) && hasText(secretAccessKey);
    }

    private static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("aws." + field + " must not be blank");
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
