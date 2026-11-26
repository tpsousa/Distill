package dev.distill.worker.document;

import dev.distill.core.DistillResult;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import dev.distill.worker.config.TableProperties;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private static final String PK = "pk";
    private static final String ID = "id";
    private static final String STATUS = "status";
    private static final String S3_KEY = "s3Key";
    private static final String RESULT = "result";
    private static final String ERROR = "error";
    private static final String ATTEMPTS = "attempts";
    private static final String CREATED_AT = "createdAt";
    private static final String PROCESSED_AT = "processedAt";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DocumentRepository(DynamoDbClient dynamoDbClient, TableProperties tableProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableProperties.name();
    }

    public void save(Document document) {
        dynamoDbClient.putItem(builder -> builder
                .tableName(tableName)
                .item(toItem(document)));
    }

    public Optional<Document> findById(String documentId) {
        GetItemResponse response = dynamoDbClient.getItem(builder -> builder
                .tableName(tableName)
                .key(key(documentId))
                .consistentRead(true));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(response.item()));
    }

    public ProcessingTransition tryMarkProcessing(String documentId) {
        Optional<Document> current = findById(documentId);
        if (current.isEmpty()) {
            return ProcessingTransition.NOT_FOUND;
        }
        DocumentStatus expected = current.get().status();
        if (!expected.canTransitionTo(DocumentStatus.PROCESSING)) {
            return ProcessingTransition.INVALID_STATE;
        }
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(documentId))
                    .updateExpression("SET #status = :processing")
                    .conditionExpression("#status = :expected")
                    .expressionAttributeNames(Map.of("#status", STATUS))
                    .expressionAttributeValues(Map.of(
                            ":processing", text(DocumentStatus.PROCESSING.name()),
                            ":expected", text(expected.name())))
                    .build());
            return ProcessingTransition.TRANSITIONED;
        } catch (ConditionalCheckFailedException exception) {
            return ProcessingTransition.INVALID_STATE;
        }
    }

    public void markDone(String documentId, DistillResult result, Instant processedAt) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(documentId))
                    .updateExpression("SET #status = :done, #result = :result, #processedAt = :processedAt")
                    .conditionExpression("#status = :processing")
                    .expressionAttributeNames(Map.of(
                            "#status", STATUS,
                            "#result", RESULT,
                            "#processedAt", PROCESSED_AT))
                    .expressionAttributeValues(Map.of(
                            ":done", text(DocumentStatus.DONE.name()),
                            ":processing", text(DocumentStatus.PROCESSING.name()),
                            ":result", resultValue(result),
                            ":processedAt", text(processedAt.toString())))
                    .build());
        } catch (ConditionalCheckFailedException exception) {
            throw new DocumentStateException("Document " + documentId + " is no longer PROCESSING", exception);
        }
    }

    public void markFailed(String documentId, String error, Instant processedAt) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(documentId))
                    .updateExpression("SET #status = :failed, #error = :error, #processedAt = :processedAt "
                            + "ADD #attempts :one")
                    .conditionExpression("#status = :processing")
                    .expressionAttributeNames(Map.of(
                            "#status", STATUS,
                            "#error", ERROR,
                            "#processedAt", PROCESSED_AT,
                            "#attempts", ATTEMPTS))
                    .expressionAttributeValues(Map.of(
                            ":failed", text(DocumentStatus.FAILED.name()),
                            ":processing", text(DocumentStatus.PROCESSING.name()),
                            ":error", text(error),
                            ":processedAt", text(processedAt.toString()),
                            ":one", number(1)))
                    .build());
        } catch (ConditionalCheckFailedException exception) {
            throw new DocumentStateException("Document " + documentId + " is no longer PROCESSING", exception);
        }
    }

    private static Map<String, AttributeValue> key(String documentId) {
        return Map.of(PK, text(partitionKey(documentId)));
    }

    private static String partitionKey(String documentId) {
        return "DOC#" + documentId;
    }

    private static Map<String, AttributeValue> toItem(Document document) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, text(partitionKey(document.id())));
        item.put(ID, text(document.id()));
        item.put(STATUS, text(document.status().name()));
        item.put(S3_KEY, text(document.s3Key()));
        if (document.result() != null) {
            item.put(RESULT, resultValue(document.result()));
        }
        if (document.error() != null) {
            item.put(ERROR, text(document.error()));
        }
        item.put(ATTEMPTS, number(document.attempts()));
        item.put(CREATED_AT, text(document.createdAt().toString()));
        if (document.processedAt() != null) {
            item.put(PROCESSED_AT, text(document.processedAt().toString()));
        }
        return item;
    }

    private static Document toDocument(Map<String, AttributeValue> item) {
        String processedAt = optionalText(item, PROCESSED_AT);
        return new Document(
                requiredText(item, ID),
                DocumentStatus.valueOf(requiredText(item, STATUS)),
                requiredText(item, S3_KEY),
                item.containsKey(RESULT) ? toResult(item.get(RESULT).m()) : null,
                optionalText(item, ERROR),
                optionalNumber(item, ATTEMPTS),
                Instant.parse(requiredText(item, CREATED_AT)),
                processedAt == null ? null : Instant.parse(processedAt));
    }

    private static DistillResult toResult(Map<String, AttributeValue> result) {
        return new DistillResult(
                requiredText(result, "summary"),
                textList(result.get("keyPoints")),
                textList(result.get("tags")),
                requiredText(result, "language"));
    }

    private static List<String> textList(AttributeValue value) {
        return value.l().stream().map(AttributeValue::s).toList();
    }

    private static AttributeValue resultValue(DistillResult result) {
        return AttributeValue.builder()
                .m(Map.of(
                        "summary", text(result.summary()),
                        "keyPoints", stringList(result.keyPoints()),
                        "tags", stringList(result.tags()),
                        "language", text(result.language())))
                .build();
    }

    private static AttributeValue stringList(List<String> values) {
        return AttributeValue.builder()
                .l(values.stream().map(DocumentRepository::text).toList())
                .build();
    }

    private static String requiredText(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        if (value == null || value.s() == null) {
            throw new IllegalStateException("Missing DynamoDB attribute: " + attribute);
        }
        return value.s();
    }

    private static String optionalText(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        return value == null ? null : value.s();
    }

    private static int optionalNumber(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        return value == null || value.n() == null ? 0 : Integer.parseInt(value.n());
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }
}
