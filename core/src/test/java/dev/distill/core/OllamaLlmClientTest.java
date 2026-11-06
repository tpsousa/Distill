package dev.distill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaLlmClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String VALID_RESULT_JSON = """
            {
              "summary": "Resumo curto",
              "keyPoints": ["ponto 1", "ponto 2"],
              "tags": ["backend", "aws"],
              "language": "pt-BR"
            }
            """;

    @Test
    void shouldSendExpectedRequestAndParseValidResponse() throws Exception {
        String response = chatResponse(VALID_RESULT_JSON, null);
        try (OllamaStubServer server = OllamaStubServer.responding(200, response)) {
            OllamaLlmClient client = clientFor(server, Duration.ofSeconds(5), 24_000);

            DistillResult result = client.distill("Texto do documento");

            assertEquals("Resumo curto", result.summary());
            assertEquals(List.of("ponto 1", "ponto 2"), result.keyPoints());
            assertEquals(List.of("backend", "aws"), result.tags());
            assertEquals("pt-BR", result.language());

            JsonNode request = server.lastRequest();
            assertTrue(request.get("think").isBoolean());
            assertTrue(request.get("think").asBoolean());
            assertEquals("qwen3:4b", request.get("model").asText());
            assertFalse(request.get("stream").asBoolean());
            assertEquals("json", request.get("format").asText());
            assertFalse(request.get("options").has("think"));
            assertEquals(0.6, request.at("/options/temperature").asDouble(), 0.0001);
            assertEquals(4096, request.at("/options/num_predict").asInt());

            assertEquals("system", request.at("/messages/0/role").asText());
            assertTrue(request.at("/messages/0/content").asText().contains("somente o JSON"));
            assertEquals("user", request.at("/messages/1/role").asText());
            assertEquals("Texto do documento", request.at("/messages/1/content").asText());
        }
    }

    @Test
    void shouldSendThinkFalseWhenDisabled() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(200, chatResponse(VALID_RESULT_JSON, null))) {
            OllamaProperties properties = new OllamaProperties(
                    server.baseUrl(), "qwen3:4b", false, Duration.ofSeconds(5), 0.6, 4096, 24_000);
            OllamaLlmClient client = new OllamaLlmClient(properties);

            client.distill("Texto do documento");

            assertFalse(server.lastRequest().get("think").asBoolean());
        }
    }

    @Test
    void shouldMarkServerErrorAsRetryable() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(500, "{\"error\":\"boom\"}")) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 24_000).distill("Texto"));

            assertTrue(exception.isRetryable());
        }
    }

    @Test
    void shouldMarkClientErrorAsNotRetryable() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(400, "{\"error\":\"bad request\"}")) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 24_000).distill("Texto"));

            assertFalse(exception.isRetryable());
        }
    }

    @Test
    void shouldMarkInvalidResponseEnvelopeAsRetryable() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(200, "not json")) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 24_000).distill("Texto"));

            assertTrue(exception.isRetryable());
        }
    }

    @Test
    void shouldMarkInvalidResultJsonAsRetryable() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(200, chatResponse("not json", null))) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 24_000).distill("Texto"));

            assertTrue(exception.isRetryable());
        }
    }

    @Test
    void shouldMarkTimeoutAsRetryable() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(200, chatResponse(VALID_RESULT_JSON, null))) {
            server.delay(Duration.ofMillis(500));

            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofMillis(50), 24_000).distill("Texto"));

            assertTrue(exception.isRetryable());
            assertTrue(exception.getMessage().contains("timed out"));
        }
    }

    @Test
    void shouldMarkEmptyContentAsRetryableWithoutExposingThinking() throws Exception {
        String thinking = "Raciocínio interno que não pode vazar";
        try (OllamaStubServer server = OllamaStubServer.responding(200, chatResponse("", thinking))) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 24_000).distill("Texto"));

            assertTrue(exception.isRetryable());
            assertTrue(exception.getMessage().contains("empty content"));
            assertFalse(exception.getMessage().contains(thinking));
        }
    }

    @Test
    void shouldIgnoreThinkingFieldFromResponse() throws Exception {
        String thinking = "Raciocínio interno que não pode vazar";
        try (OllamaStubServer server = OllamaStubServer.responding(
                200, chatResponse(VALID_RESULT_JSON, thinking))) {
            DistillResult result = clientFor(server, Duration.ofSeconds(5), 24_000)
                    .distill("Texto do documento");

            assertEquals("Resumo curto", result.summary());
            assertFalse(result.summary().contains(thinking));
            assertFalse(result.keyPoints().stream().anyMatch(point -> point.contains(thinking)));
            assertFalse(result.tags().stream().anyMatch(tag -> tag.contains(thinking)));
            assertFalse(result.language().contains(thinking));
        }
    }

    @Test
    void shouldRejectTextAboveMaxInputCharsWithoutCallingOllama() throws Exception {
        try (OllamaStubServer server = OllamaStubServer.responding(200, chatResponse(VALID_RESULT_JSON, null))) {
            LlmException exception = assertThrows(LlmException.class,
                    () -> clientFor(server, Duration.ofSeconds(5), 5).distill("123456"));

            assertFalse(exception.isRetryable());
            assertNull(server.lastRequestBody());
        }
    }

    private static OllamaLlmClient clientFor(OllamaStubServer server, Duration timeout, int maxInputChars) {
        OllamaProperties properties = new OllamaProperties(
                server.baseUrl(), "qwen3:4b", true, timeout, 0.6, 4096, maxInputChars);
        return new OllamaLlmClient(properties);
    }

    private static String chatResponse(String content, String thinking) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        if (thinking != null) {
            message.put("thinking", thinking);
        }
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "model", "qwen3:4b",
                "message", message,
                "done", true));
    }
}
