package dev.distill.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStatusTest {

    private static final Set<String> VALID_TRANSITIONS = Set.of(
            "PENDING->PROCESSING",
            "PROCESSING->DONE",
            "PROCESSING->FAILED",
            "FAILED->PROCESSING"
    );

    @ParameterizedTest
    @CsvSource({
            "PENDING, PROCESSING",
            "PROCESSING, DONE",
            "PROCESSING, FAILED",
            "FAILED, PROCESSING"
    })
    void shouldAllowValidTransition(DocumentStatus from, DocumentStatus to) {
        assertTrue(from.canTransitionTo(to));
    }

    @Test
    void shouldRejectEveryInvalidTransition() {
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                boolean expected = VALID_TRANSITIONS.contains(from.name() + "->" + to.name());
                assertEquals(expected, from.canTransitionTo(to), () -> from + " -> " + to);
            }
        }
    }

    @Test
    void shouldRejectNullTarget() {
        for (DocumentStatus status : DocumentStatus.values()) {
            assertFalse(status.canTransitionTo(null));
        }
    }
}
