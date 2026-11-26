package dev.distill.worker.s3;

import java.util.Optional;

public record S3Event(String bucket, String key, String eventName, Optional<String> documentId) {

    public S3Event {
        bucket = requireText(bucket, "bucket");
        key = requireText(key, "key");
        eventName = requireText(eventName, "eventName");
        documentId = documentId == null ? Optional.empty() : documentId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
