package dev.distill.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distill.table")
public record TableProperties(String name) {

    public TableProperties {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("distill.table.name must not be blank");
        }
    }
}
