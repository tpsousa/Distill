package dev.distill.api.document;

import dev.distill.api.document.dto.CreateDocumentRequest;
import dev.distill.api.document.dto.CreateDocumentResponse;
import dev.distill.api.document.dto.DocumentPageResponse;
import dev.distill.core.Document;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateDocumentResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return documentService.create(request);
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable String id) {
        return documentService.get(id);
    }

    @GetMapping
    public DocumentPageResponse list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String nextToken) {
        return documentService.list(limit, nextToken);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        documentService.delete(id);
    }
}
