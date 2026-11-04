package dev.distill.core;

public interface LlmClient {

    DistillResult distill(String text);
}
