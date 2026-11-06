package dev.distill.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OllamaLlmClient implements LlmClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final String PROMPT_RESOURCE = "prompts/distill-v1.txt";
    private static final String CHAT_PATH = "/api/chat";

    private final OllamaProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DistillResultParser resultParser;
    private final URI chatUri;
    private final String systemPrompt;

    public OllamaLlmClient(OllamaProperties properties) {
        this(properties,
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build(),
                new ObjectMapper(),
                new DistillResultParser(),
                loadSystemPrompt());
    }

    OllamaLlmClient(OllamaProperties properties,
                    HttpClient httpClient,
                    ObjectMapper objectMapper,
                    DistillResultParser resultParser) {
        this(properties, httpClient, objectMapper, resultParser, loadSystemPrompt());
    }

    private OllamaLlmClient(OllamaProperties properties,
                            HttpClient httpClient,
                            ObjectMapper objectMapper,
                            DistillResultParser resultParser,
                            String systemPrompt) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.resultParser = resultParser;
        this.chatUri = URI.create(stripTrailingSlash(properties.baseUrl()) + CHAT_PATH);
        this.systemPrompt = systemPrompt;
    }

    @Override
    public DistillResult distill(String text) {
        validateInput(text);

        HttpRequest request = buildRequest(text);
        LOGGER.debug("Calling Ollama model={} inputChars={}", properties.model(), text.length());

        long startedAt = System.nanoTime();
        HttpResponse<String> response = send(request);
        LOGGER.debug("Ollama model={} status={} durationMs={}", properties.model(),
                response.statusCode(), elapsedMillis(startedAt));

        ensureSuccessfulStatus(response.statusCode());
        String content = extractContent(response.body());
        return resultParser.parse(content);
    }

    private void validateInput(String text) {
        if (text == null || text.isBlank()) {
            throw new LlmException("Document text must not be blank", false);
        }
        if (text.length() > properties.maxInputChars()) {
            throw new LlmException("Document text has " + text.length()
                    + " characters, above the maximum of " + properties.maxInputChars(), false);
        }
    }

    private HttpRequest buildRequest(String text) {
        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(
                        new ChatMessage("system", systemPrompt),
                        new ChatMessage("user", text)),
                false,
                "json",
                properties.think(),
                new ChatOptions(properties.temperature(), properties.numPredict()));

        try {
            String body = objectMapper.writeValueAsString(chatRequest);
            return HttpRequest.newBuilder(chatUri)
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new LlmException("Could not serialize the Ollama request", false, exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw new LlmException("Ollama request timed out after " + properties.timeout(), true, exception);
        } catch (IOException exception) {
            throw new LlmException("Could not connect to Ollama at " + properties.baseUrl(), true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while calling Ollama", true, exception);
        }
    }

    private void ensureSuccessfulStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        boolean retryable = statusCode >= 500;
        throw new LlmException("Ollama returned HTTP " + statusCode, retryable);
    }

    private String extractContent(String responseBody) {
        ChatResponse chatResponse;
        try {
            chatResponse = objectMapper.readValue(responseBody, ChatResponse.class);
        } catch (JsonProcessingException exception) {
            throw new LlmException("Ollama response is not valid JSON", true, exception);
        }

        if (chatResponse.message() == null
                || chatResponse.message().content() == null
                || chatResponse.message().content().isBlank()) {
            throw new LlmException("Ollama returned empty content; "
                    + "the model may have exhausted num_predict during thinking", true);
        }
        return chatResponse.message().content();
    }

    private static String loadSystemPrompt() {
        try (InputStream stream = OllamaLlmClient.class.getClassLoader()
                .getResourceAsStream(PROMPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Prompt resource not found: " + PROMPT_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read prompt resource: " + PROMPT_RESOURCE, exception);
        }
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record ChatRequest(
            String model,
            List<ChatMessage> messages,
            boolean stream,
            String format,
            boolean think,
            ChatOptions options) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessage(String role, String content) {
    }

    private record ChatOptions(
            double temperature,
            @JsonProperty("num_predict") int numPredict) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(ChatMessage message) {
    }
}
