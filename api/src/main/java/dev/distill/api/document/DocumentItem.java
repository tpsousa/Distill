package dev.distill.api.document;

import dev.distill.core.DistillResult;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.List;

@DynamoDbBean
public class DocumentItem {

    private String pk;
    private String id;
    private String status;
    private String s3Key;
    private ResultItem result;
    private String error;
    private Integer attempts;
    private String createdAt;
    private String processedAt;

    public DocumentItem() {
    }

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public ResultItem getResult() {
        return result;
    }

    public void setResult(ResultItem result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(String processedAt) {
        this.processedAt = processedAt;
    }

    public static DocumentItem from(Document document) {
        DocumentItem item = new DocumentItem();
        item.setPk(partitionKey(document.id()));
        item.setId(document.id());
        item.setStatus(document.status().name());
        item.setS3Key(document.s3Key());
        item.setResult(document.result() == null ? null : ResultItem.from(document.result()));
        item.setError(document.error());
        item.setAttempts(document.attempts());
        item.setCreatedAt(document.createdAt().toString());
        item.setProcessedAt(document.processedAt() == null ? null : document.processedAt().toString());
        return item;
    }

    public Document toDocument() {
        DocumentStatus documentStatus = DocumentStatus.valueOf(status);
        DistillResult domainResult = documentStatus == DocumentStatus.DONE && result != null
                ? result.toDomain()
                : null;
        return new Document(
                id,
                documentStatus,
                s3Key,
                domainResult,
                error,
                attempts == null ? 0 : attempts,
                Instant.parse(createdAt),
                processedAt == null ? null : Instant.parse(processedAt));
    }

    public static String partitionKey(String documentId) {
        return "DOC#" + documentId;
    }

    @DynamoDbBean
    public static class ResultItem {

        private String summary;
        private List<String> keyPoints;
        private List<String> tags;
        private String language;

        public ResultItem() {
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public List<String> getKeyPoints() {
            return keyPoints;
        }

        public void setKeyPoints(List<String> keyPoints) {
            this.keyPoints = keyPoints;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        static ResultItem from(DistillResult result) {
            ResultItem item = new ResultItem();
            item.setSummary(result.summary());
            item.setKeyPoints(result.keyPoints());
            item.setTags(result.tags());
            item.setLanguage(result.language());
            return item;
        }

        DistillResult toDomain() {
            return new DistillResult(summary, keyPoints, tags, language);
        }
    }
}
