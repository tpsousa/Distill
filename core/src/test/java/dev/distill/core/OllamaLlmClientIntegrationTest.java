package dev.distill.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "OLLAMA_IT", matches = "true")
class OllamaLlmClientIntegrationTest {

    @Test
    void shouldDistillUsingRealOllama() {
        OllamaLlmClient client = new OllamaLlmClient(new OllamaProperties());

        DistillResult result = client.distill("""
                A AWS oferece serviços de computação em nuvem.
                O Amazon S3 armazena objetos e o Amazon SQS enfileira mensagens.
                Este documento fala sobre arquitetura de sistemas distribuídos.
                """);

        assertNotNull(result);
        assertFalse(result.summary().isBlank());
        assertFalse(result.keyPoints().isEmpty());
        assertFalse(result.tags().isEmpty());
        assertFalse(result.language().isBlank());
    }
}
