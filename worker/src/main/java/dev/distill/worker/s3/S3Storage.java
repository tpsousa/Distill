package dev.distill.worker.s3;

import dev.distill.worker.ProcessingException;
import dev.distill.worker.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class S3Storage {

    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    public S3Storage(S3Client s3Client, StorageProperties storageProperties) {
        this.s3Client = s3Client;
        this.storageProperties = storageProperties;
    }

    public S3Object download(String bucket, String key) {
        if (!storageProperties.bucket().equals(bucket)) {
            throw new ProcessingException("Unexpected bucket in S3 event: " + bucket, false);
        }
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(builder -> builder
                    .bucket(bucket)
                    .key(key));
            return new S3Object(response.asByteArray(), response.response().contentType());
        } catch (NoSuchKeyException exception) {
            throw new ProcessingException("Object not found in S3: " + key, false, exception);
        } catch (S3Exception exception) {
            boolean retryable = exception.statusCode() >= 500 || exception.statusCode() == 429;
            throw new ProcessingException("Could not download object from S3: " + key, retryable, exception);
        }
    }
}
