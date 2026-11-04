package dev.distill.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record DistillResult(
        @NotBlank String summary,
        @NotNull List<@NotBlank String> keyPoints,
        @NotNull List<@NotBlank String> tags,
        @NotBlank String language) {

    public DistillResult {
        summary = requireText(summary, "summary");
        keyPoints = copyTextList(keyPoints, "keyPoints");
        tags = copyTextList(tags, "tags");
        language = requireText(language, "language");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static List<String> copyTextList(List<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (String value : values) {
            requireText(value, field + " item");
        }
        return List.copyOf(values);
    }
}
