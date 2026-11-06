package dev.distill.api.document;

import dev.distill.api.config.DocumentProperties;
import dev.distill.api.document.dto.CreateDocumentRequest;
import dev.distill.api.document.dto.CreateDocumentResponse;
import dev.distill.api.document.dto.DocumentPageResponse;
import dev.distill.api.error.BadRequestException;
import dev.distill.api.error.DocumentNotFoundException;
import dev.distill.core.DistillResult;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-03T12:00:00Z");
    private static final DistillResult RESULT =
            new DistillResult("Resumo", List.of("ponto"), List.of("tag"), "pt-BR");

    @Mock
    private DocumentRepository repository;

    @Mock
    private S3Storage storage;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        DocumentProperties properties = new DocumentProperties(
                Duration.ofMinutes(5),
                50,
                List.of("text/plain", "application/pdf"),
                20,
                100);
        service = new DocumentService(repository, storage, properties);
    }

    @Test
    void shouldCreatePendingDocumentWithoutResult() {
        when(storage.presignUpload(anyString(), eq("application/pdf"), eq(Duration.ofMinutes(5))))
                .thenReturn("https://s3.example/upload");

        CreateDocumentResponse response = service.create(
                new CreateDocumentRequest("../../relatorio final.pdf", "application/pdf"));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(repository).save(captor.capture());
        Document saved = captor.getValue();

        assertEquals(response.documentId(), saved.id());
        assertEquals(DocumentStatus.PENDING, saved.status());
        assertNull(saved.result());
        assertNull(saved.error());
        assertEquals(0, saved.attempts());
        assertNotNull(saved.createdAt());
        assertNull(saved.processedAt());
        assertEquals("documents/" + response.documentId() + "/relatorio_final.pdf", saved.s3Key());
        assertEquals("https://s3.example/upload", response.uploadUrl());
        assertEquals(300, response.expiresInSeconds());
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        BadRequestException exception = assertThrows(BadRequestException.class, () -> service.create(
                new CreateDocumentRequest("documento.txt", "application/zip")));

        assertEquals("Unsupported content type: application/zip", exception.getMessage());
        verifyNoInteractions(repository, storage);
    }

    @Test
    void shouldRejectFilenameAboveMaximumLength() {
        String filename = "a".repeat(51);

        assertThrows(BadRequestException.class, () -> service.create(
                new CreateDocumentRequest(filename, "text/plain")));

        verifyNoInteractions(repository, storage);
    }

    @Test
    void shouldReturnDocumentById() {
        Document document = pendingDocument("doc-1");
        when(repository.findById("doc-1")).thenReturn(Optional.of(document));

        Document result = service.get("doc-1");

        assertEquals(document, result);
    }

    @Test
    void shouldThrowNotFoundWhenDocumentDoesNotExist() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.get("missing"));
    }

    @Test
    void shouldHideResultWhileStatusIsNotDone() {
        Document pendingWithResult = new Document(
                "doc-1", DocumentStatus.PENDING, "documents/doc-1/file.txt", RESULT, null, 0, CREATED_AT, null);
        when(repository.findById("doc-1")).thenReturn(Optional.of(pendingWithResult));

        Document result = service.get("doc-1");

        assertNull(result.result());
    }

    @Test
    void shouldUseDefaultPageSizeWhenLimitIsMissing() {
        Document document = pendingDocument("doc-1");
        when(repository.findAll(20, null))
                .thenReturn(new DocumentRepository.DocumentPage(List.of(document), "next-token"));

        DocumentPageResponse response = service.list(null, null);

        assertEquals(List.of(document), response.items());
        assertEquals("next-token", response.nextToken());
    }

    @Test
    void shouldRejectLimitAboveMaximum() {
        assertThrows(BadRequestException.class, () -> service.list(101, null));

        verifyNoInteractions(repository);
    }

    @Test
    void shouldDeleteS3ObjectBeforeDynamoDbItem() {
        Document document = pendingDocument("doc-1");
        when(repository.findById("doc-1")).thenReturn(Optional.of(document));

        service.delete("doc-1");

        InOrder order = inOrder(storage, repository);
        order.verify(storage).delete(document.s3Key());
        order.verify(repository).deleteById("doc-1");
    }

    @Test
    void shouldNotDeleteS3ObjectWhenDocumentDoesNotExist() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.delete("missing"));

        verifyNoInteractions(storage);
    }

    private static Document pendingDocument(String id) {
        return new Document(
                id, DocumentStatus.PENDING, "documents/" + id + "/file.txt", null, null, 0, CREATED_AT, null);
    }
}
