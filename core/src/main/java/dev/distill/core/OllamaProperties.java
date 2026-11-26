package dev.distill.core;

import java.time.Duration;

public record OllamaProperties(
        String baseUrl,
        String model,
        boolean think,
        Duration timeout,
        double temperature,
        int numCtx,
        int numPredict,
        int maxInputChars) {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "qwen3:4b";
    public static final boolean DEFAULT_THINK = true;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    public static final double DEFAULT_TEMPERATURE = 0.6;
    public static final int DEFAULT_NUM_CTX = 16_384;
    public static final int DEFAULT_NUM_PREDICT = 4096;

    // numCtx is the whole context window: document + system prompt + generated tokens
    // (numPredict, which includes thinking). Reserving 2048 tokens for the system prompt
    // and chat template leaves 10240 tokens for the document; at a conservative
    // 2 characters per token that is 20480 characters.
    public static final int DEFAULT_MAX_INPUT_CHARS =
            (DEFAULT_NUM_CTX - DEFAULT_NUM_PREDICT - 2_048) * 2;

    public OllamaProperties() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_THINK, DEFAULT_TIMEOUT,
                DEFAULT_TEMPERATURE, DEFAULT_NUM_CTX, DEFAULT_NUM_PREDICT, DEFAULT_MAX_INPUT_CHARS);
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
        if (numCtx <= 0) {
            throw new IllegalArgumentException("numCtx must be positive");
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
