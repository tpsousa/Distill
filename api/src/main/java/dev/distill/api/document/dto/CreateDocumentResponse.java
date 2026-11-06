package dev.distill.api.document.dto;

public record CreateDocumentResponse(
        String documentId,
        String uploadUrl,
        long expiresInSeconds) {
}
