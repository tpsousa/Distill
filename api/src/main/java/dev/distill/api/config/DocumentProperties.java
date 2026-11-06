package dev.distill.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "distill.documents")
public record DocumentProperties(
        Duration uploadUrlExpiration,
        int maxFilenameLength,
        List<String> allowedContentTypes,
        int defaultPageSize,
        int maxPageSize) {

    public DocumentProperties {
        if (uploadUrlExpiration == null || uploadUrlExpiration.isZero() || uploadUrlExpiration.isNegative()) {
            throw new IllegalArgumentException("distill.documents.upload-url-expiration must be positive");
        }
        if (maxFilenameLength <= 0) {
            throw new IllegalArgumentException("distill.documents.max-filename-length must be positive");
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            throw new IllegalArgumentException("distill.documents.allowed-content-types must not be empty");
        }
        allowedContentTypes = allowedContentTypes.stream()
                .map(String::trim)
                .filter(contentType -> !contentType.isEmpty())
                .toList();
        if (allowedContentTypes.isEmpty()) {
            throw new IllegalArgumentException("distill.documents.allowed-content-types must not be empty");
        }
        if (defaultPageSize <= 0 || maxPageSize <= 0 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("distill.documents page sizes are invalid");
        }
    }
}
