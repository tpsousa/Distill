package dev.distill.api.document.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDocumentRequest(
        @NotBlank String filename,
        @NotBlank String contentType) {
}
