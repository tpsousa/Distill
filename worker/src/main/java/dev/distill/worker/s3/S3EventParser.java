package dev.distill.worker.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class S3EventParser {

    private static final Pattern DOCUMENT_KEY = Pattern.compile("^documents/([^/]+)/.+$");

    private final ObjectMapper objectMapper;

    public S3EventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<S3Event> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new InvalidS3EventException("SQS message is not a valid JSON S3 event", exception);
        }

        JsonNode records = root.path("Records");
        if (!records.isArray()) {
            return List.of();
        }

        List<S3Event> events = new ArrayList<>();
        for (JsonNode record : records) {
            String eventSource = record.path("eventSource").asText("");
            String eventName = record.path("eventName").asText("");
            if (!"aws:s3".equals(eventSource) || !eventName.startsWith("ObjectCreated:")) {
                continue;
            }
            String bucket = record.at("/s3/bucket/name").asText("");
            String key = record.at("/s3/object/key").asText("");
            if (bucket.isBlank() || key.isBlank()) {
                continue;
            }
            String decodedKey = decodeKey(key);
            events.add(new S3Event(bucket, decodedKey, eventName, extractDocumentId(decodedKey)));
        }
        return List.copyOf(events);
    }

    private static String decodeKey(String key) {
        try {
            // S3 URL-encodes event keys and represents spaces as '+'.
            return URLDecoder.decode(key.replace("+", "%20"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new InvalidS3EventException("S3 object key is not valid URL encoding", exception);
        }
    }

    private static Optional<String> extractDocumentId(String key) {
        Matcher matcher = DOCUMENT_KEY.matcher(key);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
