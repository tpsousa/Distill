package dev.distill.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-03T12:00:00Z");
    private static final DistillResult RESULT =
            new DistillResult("Resumo", List.of("ponto"), List.of("tag"), "pt-BR");

    @Test
    void shouldCreateValidDocument() {
        assertDoesNotThrow(() -> new Document(
                "doc-1", DocumentStatus.PENDING, "docs/doc-1.pdf", null, null, 0, CREATED_AT, null));
    }

    @Test
    void shouldRejectDoneWithoutResult() {
        assertThrows(IllegalArgumentException.class, () -> new Document(
                "doc-1", DocumentStatus.DONE, "docs/doc-1.pdf", null, null, 1, CREATED_AT, CREATED_AT));
    }

    @Test
    void shouldRejectFailedWithoutError() {
        assertThrows(IllegalArgumentException.class, () -> new Document(
                "doc-1", DocumentStatus.FAILED, "docs/doc-1.pdf", null, " ", 1, CREATED_AT, CREATED_AT));
    }

    @Test
    void shouldRejectNegativeAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new Document(
                "doc-1", DocumentStatus.PENDING, "docs/doc-1.pdf", null, null, -1, CREATED_AT, null));
    }

    @Test
    void shouldRejectBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new Document(
                " ", DocumentStatus.PENDING, "docs/doc-1.pdf", null, null, 0, CREATED_AT, null));
    }

    @Test
    void shouldAllowDoneWithResult() {
        assertDoesNotThrow(() -> new Document(
                "doc-1", DocumentStatus.DONE, "docs/doc-1.pdf", RESULT, null, 1, CREATED_AT, CREATED_AT));
    }
}
