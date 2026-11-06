package dev.distill.core;

import java.time.Duration;

public record OllamaProperties(
        String baseUrl,
        String model,
        boolean think,
        Duration timeout,
        double temperature,
        int numPredict,
        int maxInputChars) {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "qwen3:4b";
    public static final boolean DEFAULT_THINK = true;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    public static final double DEFAULT_TEMPERATURE = 0.6;
    public static final int DEFAULT_NUM_PREDICT = 4096;
    public static final int DEFAULT_MAX_INPUT_CHARS = 24_000;

    public OllamaProperties() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_THINK, DEFAULT_TIMEOUT,
                DEFAULT_TEMPERATURE, DEFAULT_NUM_PREDICT, DEFAULT_MAX_INPUT_CHARS);
    }

    public OllamaProperties {
        baseUrl = requireText(baseUrl, "baseUrl");
        model = requireText(model, "model");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (temperature < 0) {
            throw new IllegalArgumentException("temperature must not be negative");
        }
        if (numPredict <= 0) {
            throw new IllegalArgumentException("numPredict must be positive");
        }
        if (maxInputChars <= 0) {
            throw new IllegalArgumentException("maxInputChars must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
