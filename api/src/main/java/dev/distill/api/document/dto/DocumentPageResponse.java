package dev.distill.api.document.dto;

import dev.distill.core.Document;

import java.util.List;

public record DocumentPageResponse(
        List<Document> items,
        String nextToken) {

    public DocumentPageResponse {
        items = List.copyOf(items);
    }
}
