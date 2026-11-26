package dev.distill.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "distill.queue")
public record QueueProperties(
        String name,
        int waitTimeSeconds,
        int maxMessages,
        Duration errorBackoff,
        boolean pollingEnabled) {

    public QueueProperties {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("distill.queue.name must not be blank");
        }
        if (waitTimeSeconds < 0 || waitTimeSeconds > 20) {
            throw new IllegalArgumentException("distill.queue.wait-time-seconds must be between 0 and 20");
        }
        if (maxMessages < 1 || maxMessages > 10) {
            throw new IllegalArgumentException("distill.queue.max-messages must be between 1 and 10");
        }
        if (errorBackoff == null || errorBackoff.isZero() || errorBackoff.isNegative()) {
            throw new IllegalArgumentException("distill.queue.error-backoff must be positive");
        }
    }
}
