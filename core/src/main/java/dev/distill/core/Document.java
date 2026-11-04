package dev.distill.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record Document(
        @NotBlank String id,
        @NotNull DocumentStatus status,
        @NotBlank String s3Key,
        DistillResult result,
        String error,
        @PositiveOrZero int attempts,
        @NotNull Instant createdAt,
        Instant processedAt) {

    public Document {
        id = requireText(id, "id");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        s3Key = requireText(s3Key, "s3Key");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (status == DocumentStatus.DONE && result == null) {
            throw new IllegalArgumentException("result must not be null when status is DONE");
        }
        if (status == DocumentStatus.FAILED && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("error must not be blank when status is FAILED");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
