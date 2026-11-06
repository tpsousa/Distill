package dev.distill.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distill.storage")
public record StorageProperties(String bucket) {

    public StorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("distill.storage.bucket must not be blank");
        }
    }
}
