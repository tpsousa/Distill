package dev.distill.worker.config;

import dev.distill.core.LlmClient;
import dev.distill.core.OllamaLlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        return switch (properties.provider().toLowerCase(Locale.ROOT)) {
            case "ollama" -> new OllamaLlmClient(properties.toOllamaProperties());
            default -> throw new IllegalStateException(
                    "Unsupported LLM provider: " + properties.provider() + " (only ollama is implemented)");
        };
    }
}
