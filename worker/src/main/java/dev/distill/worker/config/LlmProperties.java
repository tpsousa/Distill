package dev.distill.worker.config;

import dev.distill.core.OllamaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "distill.llm")
public record LlmProperties(
        String provider,
        String baseUrl,
        String model,
        boolean think,
        Duration timeout,
        double temperature,
        int numCtx,
        int numPredict,
        int maxInputChars) {

    public LlmProperties {
        provider = requireText(provider, "provider");
        baseUrl = requireText(baseUrl, "base-url");
        model = requireText(model, "model");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("distill.llm.timeout must be positive");
        }
        if (temperature < 0) {
            throw new IllegalArgumentException("distill.llm.temperature must not be negative");
        }
        if (numCtx <= 0) {
            throw new IllegalArgumentException("distill.llm.num-ctx must be positive");
        }
        if (numPredict <= 0) {
            throw new IllegalArgumentException("distill.llm.num-predict must be positive");
        }
        if (maxInputChars <= 0) {
            throw new IllegalArgumentException("distill.llm.max-input-chars must be positive");
        }
    }

    public OllamaProperties toOllamaProperties() {
        return new OllamaProperties(
                baseUrl, model, think, timeout, temperature, numCtx, numPredict, maxInputChars);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("distill.llm." + field + " must not be blank");
        }
        return value;
    }
}
