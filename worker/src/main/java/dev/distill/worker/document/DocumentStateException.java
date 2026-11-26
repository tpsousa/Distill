package dev.distill.worker.document;

public class DocumentStateException extends RuntimeException {

    public DocumentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
