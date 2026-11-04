package dev.distill.core;

public enum DocumentStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED;

    public boolean canTransitionTo(DocumentStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == DONE || target == FAILED;
            case DONE -> false;
            case FAILED -> target == PROCESSING;
        };
    }
}
