package dev.distill.worker.sqs;

import dev.distill.worker.config.QueueProperties;
import dev.distill.worker.document.DocumentProcessor;
import dev.distill.worker.document.MessageDisposition;
import dev.distill.worker.s3.InvalidS3EventException;
import dev.distill.worker.s3.S3Event;
import dev.distill.worker.s3.S3EventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.List;

@Component
public class SqsPoller implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqsPoller.class);
    private static final long STOP_TIMEOUT_MILLIS = 5_000;

    private final SqsClient sqsClient;
    private final QueueProperties properties;
    private final S3EventParser eventParser;
    private final DocumentProcessor documentProcessor;

    private volatile boolean running;
    private Thread workerThread;
    private String queueUrl;

    public SqsPoller(SqsClient sqsClient,
                     QueueProperties properties,
                     S3EventParser eventParser,
                     DocumentProcessor documentProcessor) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.eventParser = eventParser;
        this.documentProcessor = documentProcessor;
    }

    @Override
    public void start() {
        if (!properties.pollingEnabled() || running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::pollLoop, "sqs-poller");
        workerThread.setDaemon(true);
        workerThread.start();
        LOGGER.info("SQS poller started queue={}", properties.name());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(STOP_TIMEOUT_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("SQS poller stopped queue={}", properties.name());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.pollingEnabled();
    }

    private void pollLoop() {
        while (running) {
            try {
                ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl())
                        .maxNumberOfMessages(properties.maxMessages())
                        .waitTimeSeconds(properties.waitTimeSeconds())
                        .build());
                for (Message message : response.messages()) {
                    if (!running) {
                        break;
                    }
                    handle(message);
                }
            } catch (RuntimeException exception) {
                if (running) {
                    LOGGER.error("SQS polling failed queue={}", properties.name(), exception);
                    sleep(properties.errorBackoff());
                }
            }
        }
    }

    private void handle(Message message) {
        try {
            List<S3Event> events = eventParser.parse(message.body());
            if (events.isEmpty()) {
                LOGGER.info("Skipping SQS message without ObjectCreated events messageId={}",
                        message.messageId());
                delete(message);
                return;
            }
            boolean retry = false;
            for (S3Event event : events) {
                if (documentProcessor.process(event) == MessageDisposition.RETRY) {
                    retry = true;
                }
            }
            if (retry) {
                LOGGER.info("Message left for retry messageId={}", message.messageId());
            } else {
                delete(message);
            }
        } catch (InvalidS3EventException exception) {
            LOGGER.warn("Discarding malformed S3 event messageId={}", message.messageId(), exception);
            delete(message);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not handle SQS message messageId={}", message.messageId(), exception);
        }
    }

    private void delete(Message message) {
        try {
            sqsClient.deleteMessage(builder -> builder
                    .queueUrl(queueUrl())
                    .receiptHandle(message.receiptHandle()));
        } catch (RuntimeException exception) {
            LOGGER.error("Could not delete SQS message messageId={}", message.messageId(), exception);
        }
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqsClient.getQueueUrl(builder -> builder.queueName(properties.name())).queueUrl();
        }
        return queueUrl;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
