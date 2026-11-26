package dev.distill.worker;

import dev.distill.core.DistillResult;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import dev.distill.core.LlmClient;
import dev.distill.worker.document.DocumentRepository;
import dev.distill.worker.document.ProcessingTransition;
import dev.distill.worker.sqs.SqsPoller;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WorkerIntegrationTest.FakeLlmConfiguration.class)
class WorkerIntegrationTest {

    private static final String BUCKET = "distill-documents-it";
    private static final String QUEUE = "distill-queue-it";
    private static final String TABLE = "distill-documents-it";
    private static final DistillResult RESULT =
            new DistillResult("Resumo fake", List.of("ponto"), List.of("tag"), "pt-BR");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(
                    LocalStackContainer.Service.S3,
                    LocalStackContainer.Service.SQS,
                    LocalStackContainer.Service.DYNAMODB);

    private static S3Client s3Client;
    private static SqsClient sqsClient;
    private static DynamoDbClient dynamoDbClient;
    private static SqsPoller pollerRef;

    @Autowired
    private DocumentRepository repository;

    @Autowired
    private SqsPoller poller;

    @Autowired
    private FakeLlmClient llmClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.access-key-id", () -> "test");
        registry.add("aws.secret-access-key", () -> "test");
        registry.add("aws.s3-path-style-access", () -> true);
        registry.add("distill.storage.bucket", () -> BUCKET);
        registry.add("distill.queue.name", () -> QUEUE);
        registry.add("distill.queue.wait-time-seconds", () -> 1);
        registry.add("distill.queue.max-messages", () -> 1);
        registry.add("distill.queue.error-backoff", () -> "1s");
        registry.add("distill.queue.polling-enabled", () -> true);
        registry.add("distill.table.name", () -> TABLE);
        registry.add("distill.llm.provider", () -> "ollama");
        registry.add("distill.llm.base-url", () -> "http://localhost:11434");
        registry.add("distill.llm.model", () -> "qwen3:4b");
        registry.add("distill.llm.think", () -> true);
        registry.add("distill.llm.timeout", () -> "5s");
        registry.add("distill.llm.temperature", () -> 0.6);
        registry.add("distill.llm.num-ctx", () -> 16384);
        registry.add("distill.llm.num-predict", () -> 4096);
        registry.add("distill.llm.max-input-chars", () -> 20480);
    }

    @BeforeAll
    static void createInfrastructure() {
        s3Client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        sqsClient = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();

        s3Client.createBucket(builder -> builder.bucket(BUCKET));

        sqsClient.createQueue(builder -> builder
                .queueName(QUEUE)
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "300")));
        String queueUrl = sqsClient.getQueueUrl(builder -> builder.queueName(QUEUE)).queueUrl();
        String queueArn = sqsClient.getQueueAttributes(builder -> builder
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        String queuePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": { "Service": "s3.amazonaws.com" },
                      "Action": "sqs:SendMessage",
                      "Resource": "%s",
                      "Condition": { "ArnEquals": { "aws:SourceArn": "arn:aws:s3:::%s" } }
                    }
                  ]
                }
                """.formatted(queueArn, BUCKET);
        sqsClient.setQueueAttributes(builder -> builder
                .queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, queuePolicy)));

        s3Client.putBucketNotificationConfiguration(builder -> builder
                .bucket(BUCKET)
                .notificationConfiguration(notification -> notification
                        .queueConfigurations(QueueConfiguration.builder()
                                .queueArn(queueArn)
                                .events(Event.S3_OBJECT_CREATED)
                                .build())));

        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(TABLE)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("pk")
                        .keyType(KeyType.HASH)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        dynamoDbClient.waiter().waitUntilTableExists(builder -> builder.tableName(TABLE));
    }

    @AfterAll
    static void stopPoller() {
        if (pollerRef != null) {
            pollerRef.stop();
        }
    }

    @AfterAll
    static void closeClients() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (sqsClient != null) {
            sqsClient.close();
        }
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    @BeforeEach
    void clearLlmCalls() {
        pollerRef = poller;
        llmClient.clear();
    }

    @Test
    void shouldProcessTextDocumentToDone() throws Exception {
        Document document = pendingDocument("nota.txt");
        repository.save(document);

        s3Client.putObject(
                builder -> builder.bucket(BUCKET).key(document.s3Key()).contentType("text/plain"),
                RequestBody.fromString("Conteudo do documento de teste"));

        Document processed = awaitStatus(document.id(), DocumentStatus.DONE);

        assertEquals(RESULT, processed.result());
        assertNotNull(processed.processedAt());
        assertTrue(llmClient.texts().stream().anyMatch(text -> text.contains("Conteudo")));
        assertEquals(ProcessingTransition.INVALID_STATE, repository.tryMarkProcessing(processed.id()));
    }

    @Test
    void shouldMarkFailedAndIncrementAttempts() {
        Document document = pendingDocument("falha.txt");
        repository.save(document);

        assertEquals(ProcessingTransition.TRANSITIONED, repository.tryMarkProcessing(document.id()));
        repository.markFailed(document.id(), "boom", Instant.now());

        Document failed = repository.findById(document.id()).orElseThrow();
        assertEquals(DocumentStatus.FAILED, failed.status());
        assertEquals(1, failed.attempts());
        assertEquals("boom", failed.error());
        assertNotNull(failed.processedAt());
    }

    @Test
    void shouldProcessPdfDocumentToDone() throws Exception {
        Document document = pendingDocument("relatorio.pdf");
        repository.save(document);

        s3Client.putObject(
                builder -> builder.bucket(BUCKET).key(document.s3Key()).contentType("application/pdf"),
                RequestBody.fromBytes(pdfWithText("Relatorio de teste")));

        Document processed = awaitStatus(document.id(), DocumentStatus.DONE);

        assertEquals(RESULT, processed.result());
        assertTrue(llmClient.texts().stream().anyMatch(text -> text.contains("Relatorio")));
    }

    private Document awaitStatus(String documentId, DocumentStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Optional<Document> current = Optional.empty();
        while (System.nanoTime() < deadline) {
            current = repository.findById(documentId);
            if (current.isPresent() && current.get().status() == expected) {
                return current.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Document " + documentId + " did not reach " + expected
                + "; last status=" + current.map(Document::status).orElse(null));
    }

    private static Document pendingDocument(String fileName) {
        String documentId = UUID.randomUUID().toString();
        return new Document(
                documentId,
                DocumentStatus.PENDING,
                "documents/" + documentId + "/" + fileName,
                null,
                null,
                0,
                Instant.now(),
                null);
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    @TestConfiguration
    static class FakeLlmConfiguration {

        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    static class FakeLlmClient implements LlmClient {

        private final List<String> texts = new CopyOnWriteArrayList<>();

        @Override
        public DistillResult distill(String text) {
            texts.add(text);
            return RESULT;
        }

        List<String> texts() {
            return List.copyOf(texts);
        }

        void clear() {
            texts.clear();
        }
    }
}
