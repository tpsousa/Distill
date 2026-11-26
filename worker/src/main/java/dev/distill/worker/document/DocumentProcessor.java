package dev.distill.worker.document;

import dev.distill.core.DistillResult;
import dev.distill.core.LlmClient;
import dev.distill.core.LlmException;
import dev.distill.worker.ProcessingException;
import dev.distill.worker.s3.S3Event;
import dev.distill.worker.s3.S3Object;
import dev.distill.worker.s3.S3Storage;
import dev.distill.worker.text.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DocumentProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessor.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentRepository repository;
    private final S3Storage storage;
    private final TextExtractor textExtractor;
    private final LlmClient llmClient;

    public DocumentProcessor(DocumentRepository repository,
                             S3Storage storage,
                             TextExtractor textExtractor,
                             LlmClient llmClient) {
        this.repository = repository;
        this.storage = storage;
        this.textExtractor = textExtractor;
        this.llmClient = llmClient;
    }

    public MessageDisposition process(S3Event event) {
        if (event.documentId().isEmpty()) {
            LOGGER.warn("Skipping S3 object outside the documents/ prefix key={}", event.key());
            return MessageDisposition.DELETE;
        }
        String documentId = event.documentId().get();

        ProcessingTransition transition = repository.tryMarkProcessing(documentId);
        if (transition == ProcessingTransition.NOT_FOUND) {
            LOGGER.warn("Document not found in DynamoDB, skipping id={}", documentId);
            return MessageDisposition.DELETE;
        }
        if (transition == ProcessingTransition.INVALID_STATE) {
            LOGGER.info("Document is not in a processable state, skipping id={}", documentId);
            return MessageDisposition.DELETE;
        }

        try {
            S3Object object = storage.download(event.bucket(), event.key());
            String text = textExtractor.extract(object.content(), object.contentType(), event.key());
            if (text == null || text.isBlank()) {
                throw new ProcessingException("Extracted text is empty", false);
            }
            DistillResult result = llmClient.distill(text);
            repository.markDone(documentId, result, Instant.now());
            LOGGER.info("Document processed successfully id={}", documentId);
            return MessageDisposition.DELETE;
        } catch (LlmException exception) {
            return fail(documentId, exception.getMessage(), exception.isRetryable());
        } catch (ProcessingException exception) {
            return fail(documentId, exception.getMessage(), exception.isRetryable());
        } catch (DocumentStateException exception) {
            LOGGER.warn("Document state changed while finishing, skipping id={}", documentId);
            return MessageDisposition.DELETE;
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected error processing document id={}", documentId, exception);
            return fail(documentId, "Unexpected error: " + exception.getClass().getSimpleName(), true);
        }
    }

    private MessageDisposition fail(String documentId, String message, boolean retryable) {
        String error = shorten(message);
        try {
            repository.markFailed(documentId, error, Instant.now());
        } catch (RuntimeException exception) {
            LOGGER.error("Could not mark document as FAILED id={}", documentId, exception);
        }
        LOGGER.warn("Document processing failed id={} retryable={} error={}", documentId, retryable, error);
        return retryable ? MessageDisposition.RETRY : MessageDisposition.DELETE;
    }

    private static String shorten(String message) {
        String value = message == null || message.isBlank() ? "Unknown error" : message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
