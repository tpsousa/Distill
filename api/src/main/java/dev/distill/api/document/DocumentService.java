package dev.distill.api.document;

import dev.distill.api.config.DocumentProperties;
import dev.distill.api.document.dto.CreateDocumentRequest;
import dev.distill.api.document.dto.CreateDocumentResponse;
import dev.distill.api.document.dto.DocumentPageResponse;
import dev.distill.api.error.BadRequestException;
import dev.distill.api.error.DocumentNotFoundException;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository repository;
    private final S3Storage storage;
    private final DocumentProperties properties;

    public DocumentService(DocumentRepository repository, S3Storage storage, DocumentProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
    }

    public CreateDocumentResponse create(CreateDocumentRequest request) {
        String filename = validateFilename(request.filename());
        String contentType = validateContentType(request.contentType());

        String documentId = UUID.randomUUID().toString();
        String s3Key = "documents/" + documentId + "/" + sanitizeFilename(filename);
        String uploadUrl = storage.presignUpload(s3Key, contentType, properties.uploadUrlExpiration());

        Document document = new Document(
                documentId,
                DocumentStatus.PENDING,
                s3Key,
                null,
                null,
                0,
                Instant.now(),
                null);
        repository.save(document);

        LOGGER.info("Created document id={} s3Key={} contentType={}", documentId, s3Key, contentType);
        return new CreateDocumentResponse(documentId, uploadUrl, properties.uploadUrlExpiration().toSeconds());
    }

    public Document get(String documentId) {
        return visible(repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId)));
    }

    public DocumentPageResponse list(Integer limit, String nextToken) {
        int pageSize = resolvePageSize(limit);
        DocumentRepository.DocumentPage page = repository.findAll(pageSize, nextToken);
        List<Document> documents = page.items().stream()
                .map(this::visible)
                .toList();
        return new DocumentPageResponse(documents, page.nextToken());
    }

    public void delete(String documentId) {
        Document document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        storage.delete(document.s3Key());
        repository.deleteById(documentId);
        LOGGER.info("Deleted document id={}", documentId);
    }

    private int resolvePageSize(Integer limit) {
        if (limit == null) {
            return properties.defaultPageSize();
        }
        if (limit <= 0 || limit > properties.maxPageSize()) {
            throw new BadRequestException("limit must be between 1 and " + properties.maxPageSize());
        }
        return limit;
    }

    private String validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BadRequestException("filename must not be blank");
        }
        String normalized = filename.trim();
        if (normalized.length() > properties.maxFilenameLength()) {
            throw new BadRequestException("filename must have at most "
                    + properties.maxFilenameLength() + " characters");
        }
        return normalized;
    }

    private String validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("contentType must not be blank");
        }
        return properties.allowedContentTypes().stream()
                .filter(allowed -> allowed.equalsIgnoreCase(contentType))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unsupported content type: " + contentType));
    }

    private Document visible(Document document) {
        if (document.status() == DocumentStatus.DONE || document.result() == null) {
            return document;
        }
        return new Document(
                document.id(),
                document.status(),
                document.s3Key(),
                null,
                document.error(),
                document.attempts(),
                document.createdAt(),
                document.processedAt());
    }

    static String sanitizeFilename(String filename) {
        String normalized = filename.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        String sanitized = normalized
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            return "file";
        }
        return sanitized;
    }
}
