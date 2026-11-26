package dev.distill.worker;

public class ProcessingException extends RuntimeException {

    private final boolean retryable;

    public ProcessingException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ProcessingException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
