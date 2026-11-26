package dev.distill.worker.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3EventParserTest {

    private final S3EventParser parser = new S3EventParser(new ObjectMapper());

    @Test
    void shouldParseObjectCreatedEvent() {
        List<S3Event> events = parser.parse(event("ObjectCreated:Put", "documents/doc-1/file.txt"));

        assertEquals(1, events.size());
        S3Event event = events.get(0);
        assertEquals("distill-documents", event.bucket());
        assertEquals("documents/doc-1/file.txt", event.key());
        assertEquals("ObjectCreated:Put", event.eventName());
        assertEquals("doc-1", event.documentId().orElseThrow());
    }

    @Test
    void shouldDecodeUrlEncodedKeyWithSpecialCharacters() {
        List<S3Event> events = parser.parse(event(
                "ObjectCreated:Put",
                "documents/doc-2/relat%C3%B3rio+final%2Bv2.pdf"));

        assertEquals(1, events.size());
        assertEquals("documents/doc-2/relatório final+v2.pdf", events.get(0).key());
        assertEquals("doc-2", events.get(0).documentId().orElseThrow());
    }

    @Test
    void shouldIgnoreS3TestEvent() {
        String testEvent = """
                {
                  "Service": "Amazon S3",
                  "Event": "s3:TestEvent",
                  "Time": "2026-11-06T01:49:39.681Z",
                  "Bucket": "distill-documents",
                  "RequestId": "f8587a8b",
                  "HostId": "host"
                }
                """;

        assertTrue(parser.parse(testEvent).isEmpty());
    }

    @Test
    void shouldIgnoreNonObjectCreatedEvents() {
        assertTrue(parser.parse(event("ObjectRemoved:Delete", "documents/doc-3/file.txt")).isEmpty());
    }

    @Test
    void shouldReturnEventWithoutDocumentIdWhenKeyIsOutsidePrefix() {
        List<S3Event> events = parser.parse(event("ObjectCreated:Put", "other/doc-4/file.txt"));

        assertEquals(1, events.size());
        assertTrue(events.get(0).documentId().isEmpty());
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThrows(InvalidS3EventException.class, () -> parser.parse("not json"));
    }

    private static String event(String eventName, String key) {
        return """
                {
                  "Records": [
                    {
                      "eventVersion": "2.1",
                      "eventSource": "aws:s3",
                      "awsRegion": "us-east-1",
                      "eventName": "%s",
                      "s3": {
                        "s3SchemaVersion": "1.0",
                        "bucket": { "name": "distill-documents" },
                        "object": { "key": "%s", "size": 10 }
                      }
                    }
                  ]
                }
                """.formatted(eventName, key);
    }
}
