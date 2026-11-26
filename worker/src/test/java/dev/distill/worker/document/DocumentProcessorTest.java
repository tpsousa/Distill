package dev.distill.worker.document;

import dev.distill.core.DistillResult;
import dev.distill.core.LlmClient;
import dev.distill.core.LlmException;
import dev.distill.worker.ProcessingException;
import dev.distill.worker.s3.S3Event;
import dev.distill.worker.s3.S3Object;
import dev.distill.worker.s3.S3Storage;
import dev.distill.worker.text.TextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessorTest {

    private static final String DOCUMENT_ID = "doc-1";
    private static final String BUCKET = "distill-documents";
    private static final String KEY = "documents/doc-1/file.txt";
    private static final S3Event EVENT = new S3Event(
            BUCKET, KEY, "ObjectCreated:Put", Optional.of(DOCUMENT_ID));
    private static final byte[] CONTENT = "texto do documento".getBytes(StandardCharsets.UTF_8);
    private static final DistillResult RESULT =
            new DistillResult("Resumo", List.of("ponto"), List.of("tag"), "pt-BR");

    @Mock
    private DocumentRepository repository;

    @Mock
    private S3Storage storage;

    @Mock
    private TextExtractor textExtractor;

    @Mock
    private LlmClient llmClient;

    private DocumentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DocumentProcessor(repository, storage, textExtractor, llmClient);
    }

    @Test
    void shouldMarkDocumentAsDoneWhenLlmSucceeds() {
        givenProcessableDocument();
        when(llmClient.distill("texto do documento")).thenReturn(RESULT);

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.DELETE, disposition);
        verify(repository).markDone(eq(DOCUMENT_ID), eq(RESULT), any(Instant.class));
        verify(repository, never()).markFailed(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldKeepMessageForRetryWhenLlmFailureIsRetryable() {
        givenProcessableDocument();
        when(llmClient.distill("texto do documento"))
                .thenThrow(new LlmException("Ollama request timed out", true));

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.RETRY, disposition);
        verify(repository).markFailed(eq(DOCUMENT_ID), eq("Ollama request timed out"), any(Instant.class));
        verify(repository, never()).markDone(anyString(), any(DistillResult.class), any(Instant.class));
    }

    @Test
    void shouldDeleteMessageWhenLlmFailureIsNotRetryable() {
        givenProcessableDocument();
        when(llmClient.distill("texto do documento"))
                .thenThrow(new LlmException("Invalid model response", false));

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.DELETE, disposition);
        verify(repository).markFailed(eq(DOCUMENT_ID), eq("Invalid model response"), any(Instant.class));
    }

    @Test
    void shouldSkipWhenDocumentIsAlreadyProcessing() {
        when(repository.tryMarkProcessing(DOCUMENT_ID)).thenReturn(ProcessingTransition.INVALID_STATE);

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.DELETE, disposition);
        verifyNoInteractions(storage, textExtractor, llmClient);
        verify(repository, never()).markFailed(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldSkipWhenDocumentDoesNotExist() {
        when(repository.tryMarkProcessing(DOCUMENT_ID)).thenReturn(ProcessingTransition.NOT_FOUND);

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.DELETE, disposition);
        verifyNoInteractions(storage, textExtractor, llmClient);
        verify(repository, never()).markFailed(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldMarkFailedWhenExtractedTextIsEmpty() {
        givenProcessableDocument();
        when(textExtractor.extract(CONTENT, "text/plain", KEY)).thenReturn("   ");

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.DELETE, disposition);
        verify(repository).markFailed(eq(DOCUMENT_ID), eq("Extracted text is empty"), any(Instant.class));
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldKeepMessageForRetryWhenS3DownloadFailsTemporarily() {
        when(repository.tryMarkProcessing(DOCUMENT_ID)).thenReturn(ProcessingTransition.TRANSITIONED);
        when(storage.download(BUCKET, KEY))
                .thenThrow(new ProcessingException("Could not download object from S3", true));

        MessageDisposition disposition = processor.process(EVENT);

        assertEquals(MessageDisposition.RETRY, disposition);
        verify(repository).markFailed(
                eq(DOCUMENT_ID), eq("Could not download object from S3"), any(Instant.class));
    }

    private void givenProcessableDocument() {
        when(repository.tryMarkProcessing(DOCUMENT_ID)).thenReturn(ProcessingTransition.TRANSITIONED);
        when(storage.download(BUCKET, KEY)).thenReturn(new S3Object(CONTENT, "text/plain"));
        when(textExtractor.extract(CONTENT, "text/plain", KEY)).thenReturn("texto do documento");
    }
}
