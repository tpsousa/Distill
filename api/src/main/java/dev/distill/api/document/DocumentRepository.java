package dev.distill.api.document;

import dev.distill.api.config.TableProperties;
import dev.distill.api.error.BadRequestException;
import dev.distill.core.Document;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private static final String PARTITION_KEY = "pk";

    private final DynamoDbTable<DocumentItem> table;

    public DocumentRepository(DynamoDbEnhancedClient enhancedClient, TableProperties tableProperties) {
        this.table = enhancedClient.table(tableProperties.name(), TableSchema.fromBean(DocumentItem.class));
    }

    public void save(Document document) {
        table.putItem(DocumentItem.from(document));
    }

    public Optional<Document> findById(String documentId) {
        DocumentItem item = table.getItem(Key.builder()
                .partitionValue(DocumentItem.partitionKey(documentId))
                .build());
        return Optional.ofNullable(item).map(DocumentItem::toDocument);
    }

    public DocumentPage findAll(int limit, String nextToken) {
        ScanEnhancedRequest.Builder request = ScanEnhancedRequest.builder().limit(limit);
        if (nextToken != null && !nextToken.isBlank()) {
            request.exclusiveStartKey(decodeNextToken(nextToken));
        }

        Page<DocumentItem> page = table.scan(request.build()).iterator().next();
        List<Document> documents = page.items().stream()
                .map(DocumentItem::toDocument)
                .toList();
        return new DocumentPage(documents, encodeNextToken(page.lastEvaluatedKey()));
    }

    public void deleteById(String documentId) {
        table.deleteItem(Key.builder()
                .partitionValue(DocumentItem.partitionKey(documentId))
                .build());
    }

    private static String encodeNextToken(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        AttributeValue partitionKey = lastEvaluatedKey.get(PARTITION_KEY);
        if (partitionKey == null || partitionKey.s() == null || partitionKey.s().isBlank()) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(partitionKey.s().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodeNextToken(String nextToken) {
        try {
            String partitionKey = new String(
                    Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8);
            if (!partitionKey.startsWith("DOC#")) {
                throw new BadRequestException("Invalid nextToken");
            }
            return Map.of(PARTITION_KEY, AttributeValue.builder().s(partitionKey).build());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid nextToken", exception);
        }
    }

    public record DocumentPage(List<Document> items, String nextToken) {
    }
}
