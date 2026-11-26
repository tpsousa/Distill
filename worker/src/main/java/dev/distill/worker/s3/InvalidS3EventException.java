package dev.distill.worker.s3;

public class InvalidS3EventException extends RuntimeException {

    public InvalidS3EventException(String message, Throwable cause) {
        super(message, cause);
    }
}
