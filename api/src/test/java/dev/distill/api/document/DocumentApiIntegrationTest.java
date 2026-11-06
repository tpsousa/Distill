package dev.distill.api.document;

import dev.distill.api.document.dto.CreateDocumentRequest;
import dev.distill.api.document.dto.CreateDocumentResponse;
import dev.distill.api.document.dto.DocumentPageResponse;
import dev.distill.api.error.ApiError;
import dev.distill.core.Document;
import dev.distill.core.DocumentStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentApiIntegrationTest {

    private static final String BUCKET = "distill-documents-it";
    private static final String TABLE = "distill-documents-it";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.DYNAMODB);

    @Autowired
    private TestRestTemplate restTemplate;

    private static S3Client s3Client;
    private static DynamoDbClient dynamoDbClient;

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.access-key-id", () -> "test");
        registry.add("aws.secret-access-key", () -> "test");
        registry.add("aws.s3-path-style-access", () -> true);
        registry.add("distill.storage.bucket", () -> BUCKET);
        registry.add("distill.table.name", () -> TABLE);
    }

    @BeforeAll
    static void createInfrastructure() {
        s3Client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();

        s3Client.createBucket(builder -> builder.bucket(BUCKET));
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
    static void closeClients() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    @Test
    void shouldCreateUploadListAndDeleteDocument() throws Exception {
        CreateDocumentResponse first = create("relatorio final.pdf", "application/pdf");
        CreateDocumentResponse second = create("notas.txt", "text/plain");
        CreateDocumentResponse third = create("outro.txt", "text/plain");

        assertEquals(300, first.expiresInSeconds());
        assertNotNull(first.uploadUrl());

        ResponseEntity<Document> found = restTemplate.getForEntity(
                "/documents/" + first.documentId(), Document.class);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertNotNull(found.getBody());
        assertEquals(DocumentStatus.PENDING, found.getBody().status());
        assertNull(found.getBody().result());
        assertEquals("documents/" + first.documentId() + "/relatorio_final.pdf",
                found.getBody().s3Key());

        byte[] content = "%PDF-1.4 integration test".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> upload = upload(first.uploadUrl(), "application/pdf", content);
        assertEquals(HttpStatus.OK.value(), upload.statusCode());

        byte[] stored = s3Client.getObjectAsBytes(builder -> builder
                        .bucket(BUCKET)
                        .key(found.getBody().s3Key()))
                .asByteArray();
        assertArrayEquals(content, stored);

        ResponseEntity<DocumentPageResponse> firstPage = restTemplate.exchange(
                "/documents?limit=2", HttpMethod.GET, null, DocumentPageResponse.class);
        assertEquals(HttpStatus.OK, firstPage.getStatusCode());
        assertNotNull(firstPage.getBody());
        assertEquals(2, firstPage.getBody().items().size());
        assertNotNull(firstPage.getBody().nextToken());

        ResponseEntity<DocumentPageResponse> secondPage = restTemplate.exchange(
                "/documents?limit=2&nextToken=" + firstPage.getBody().nextToken(),
                HttpMethod.GET, null, DocumentPageResponse.class);
        assertEquals(HttpStatus.OK, secondPage.getStatusCode());
        assertNotNull(secondPage.getBody());
        assertEquals(1, secondPage.getBody().items().size());
        assertNull(secondPage.getBody().nextToken());

        List<String> allIds = new ArrayList<>();
        firstPage.getBody().items().forEach(document -> allIds.add(document.id()));
        secondPage.getBody().items().forEach(document -> allIds.add(document.id()));
        assertEquals(3, allIds.size());
        assertTrue(allIds.containsAll(List.of(
                first.documentId(), second.documentId(), third.documentId())));

        assertEquals(HttpStatus.NO_CONTENT.value(), delete(first.documentId()));
        assertEquals(HttpStatus.NO_CONTENT.value(), delete(second.documentId()));
        assertEquals(HttpStatus.NO_CONTENT.value(), delete(third.documentId()));

        ResponseEntity<ApiError> notFound = restTemplate.getForEntity(
                "/documents/" + first.documentId(), ApiError.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertNotNull(notFound.getBody());
        assertEquals(404, notFound.getBody().status());
        assertTrue(notFound.getBody().message().contains("not found"));
        assertFalse(notFound.getBody().message().contains("Exception"));

        S3Exception exception = assertThrows(S3Exception.class, () -> s3Client.headObject(builder -> builder
                .bucket(BUCKET)
                .key(found.getBody().s3Key())));
        assertEquals(404, exception.statusCode());
    }

    @Test
    void shouldReturnBadRequestForUnsupportedContentType() {
        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/documents",
                new CreateDocumentRequest("arquivo.txt", "application/zip"),
                ApiError.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().status());
        assertTrue(response.getBody().message().contains("Unsupported content type"));
    }

    private CreateDocumentResponse create(String filename, String contentType) {
        ResponseEntity<CreateDocumentResponse> response = restTemplate.postForEntity(
                "/documents",
                new CreateDocumentRequest(filename, contentType),
                CreateDocumentResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private int delete(String documentId) {
        return restTemplate.exchange(
                "/documents/" + documentId,
                HttpMethod.DELETE,
                null,
                Void.class).getStatusCode().value();
    }

    private static HttpResponse<String> upload(String url, String contentType, byte[] content)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }
}
