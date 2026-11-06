package dev.distill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

final class OllamaStubServer implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile long delayMillis;

    private OllamaStubServer(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            delay();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (IOException ignored) {
                // The client may have timed out before the response was written.
            }
        });
        server.start();
    }

    static OllamaStubServer responding(int statusCode, String responseBody) throws IOException {
        return new OllamaStubServer(statusCode, responseBody);
    }

    void delay(Duration duration) {
        this.delayMillis = duration.toMillis();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String lastRequestBody() {
        return lastRequestBody.get();
    }

    JsonNode lastRequest() {
        String body = lastRequestBody.get();
        if (body == null) {
            throw new IllegalStateException("No request was received");
        }
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (IOException exception) {
            throw new IllegalStateException("Request body is not valid JSON", exception);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void delay() {
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
