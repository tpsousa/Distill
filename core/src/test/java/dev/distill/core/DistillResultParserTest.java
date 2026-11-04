package dev.distill.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistillResultParserTest {

    private final DistillResultParser parser = new DistillResultParser();

    @Test
    void shouldParseValidJson() {
        String json = """
                {
                  "summary": "Resumo curto",
                  "keyPoints": ["ponto 1", "ponto 2"],
                  "tags": ["backend", "aws"],
                  "language": "pt-BR"
                }
                """;

        DistillResult result = parser.parse(json);

        assertEquals("Resumo curto", result.summary());
        assertEquals(List.of("ponto 1", "ponto 2"), result.keyPoints());
        assertEquals(List.of("backend", "aws"), result.tags());
        assertEquals("pt-BR", result.language());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{",
            "não é json",
            "[1, 2, 3]",
            "{\"summary\": 1, \"keyPoints\": [], \"tags\": [], \"language\": \"pt-BR\"}"
    })
    void shouldRejectMalformedJson(String rawJson) {
        LlmException exception = assertThrows(LlmException.class, () -> parser.parse(rawJson));

        assertTrue(exception.isRetryable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectEmptyJson(String rawJson) {
        LlmException exception = assertThrows(LlmException.class, () -> parser.parse(rawJson));

        assertTrue(exception.isRetryable());
    }

    @Test
    void shouldRejectNullJson() {
        LlmException exception = assertThrows(LlmException.class, () -> parser.parse(null));

        assertTrue(exception.isRetryable());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"keyPoints\": [\"ponto\"], \"tags\": [\"tag\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"tags\": [\"tag\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"keyPoints\": [\"ponto\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"keyPoints\": [\"ponto\"], \"tags\": [\"tag\"]}"
    })
    void shouldRejectIncompleteJson(String rawJson) {
        LlmException exception = assertThrows(LlmException.class, () -> parser.parse(rawJson));

        assertTrue(exception.isRetryable());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"summary\": \" \", \"keyPoints\": [\"ponto\"], \"tags\": [\"tag\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"keyPoints\": [\"\"], \"tags\": [\"tag\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"keyPoints\": [\"ponto\"], \"tags\": [\"\"], \"language\": \"pt-BR\"}",
            "{\"summary\": \"Resumo\", \"keyPoints\": [\"ponto\"], \"tags\": [\"tag\"], \"language\": \" \"}"
    })
    void shouldRejectBlankRequiredFields(String rawJson) {
        LlmException exception = assertThrows(LlmException.class, () -> parser.parse(rawJson));

        assertTrue(exception.isRetryable());
    }
}
