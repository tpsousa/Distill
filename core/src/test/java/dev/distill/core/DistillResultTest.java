package dev.distill.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistillResultTest {

    @Test
    void shouldCreateValidResult() {
        assertDoesNotThrow(() -> new DistillResult(
                "Resumo", List.of("ponto"), List.of("tag"), "pt-BR"));
    }

    @Test
    void shouldRejectBlankSummary() {
        assertThrows(IllegalArgumentException.class, () -> new DistillResult(
                " ", List.of("ponto"), List.of("tag"), "pt-BR"));
    }

    @Test
    void shouldRejectMissingLists() {
        assertThrows(IllegalArgumentException.class, () -> new DistillResult(
                "Resumo", null, List.of("tag"), "pt-BR"));
        assertThrows(IllegalArgumentException.class, () -> new DistillResult(
                "Resumo", List.of("ponto"), null, "pt-BR"));
    }

    @Test
    void shouldRejectBlankListItems() {
        assertThrows(IllegalArgumentException.class, () -> new DistillResult(
                "Resumo", List.of(" "), List.of("tag"), "pt-BR"));
        assertThrows(IllegalArgumentException.class, () -> new DistillResult(
                "Resumo", List.of("ponto"), List.of(" "), "pt-BR"));
    }
}
