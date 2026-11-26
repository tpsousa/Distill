package dev.distill.worker.s3;

public record S3Object(byte[] content, String contentType) {
}
