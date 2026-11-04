#!/usr/bin/env bash
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

BUCKET="distill-documents"
MAIN_QUEUE="distill-queue"
DLQ="distill-dlq"
TABLE="distill-documents"

if ! awslocal s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  awslocal s3api create-bucket --bucket "$BUCKET" --region us-east-1 >/dev/null
fi

if ! awslocal sqs get-queue-url --queue-name "$DLQ" >/dev/null 2>&1; then
  awslocal sqs create-queue --queue-name "$DLQ" >/dev/null
fi

if ! awslocal sqs get-queue-url --queue-name "$MAIN_QUEUE" >/dev/null 2>&1; then
  awslocal sqs create-queue --queue-name "$MAIN_QUEUE" >/dev/null
fi

if ! awslocal dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  awslocal dynamodb create-table \
    --table-name "$TABLE" \
    --attribute-definitions AttributeName=pk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
fi

DLQ_URL=$(awslocal sqs get-queue-url --queue-name "$DLQ" --query QueueUrl --output text)
MAIN_QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "$MAIN_QUEUE" --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
MAIN_QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "$MAIN_QUEUE_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

QUEUE_ATTRIBUTES=$(python3 - "$MAIN_QUEUE_ARN" "$DLQ_ARN" "$BUCKET" <<'PY'
import json
import sys

main_queue_arn, dlq_arn, bucket = sys.argv[1], sys.argv[2], sys.argv[3]
source_arn = f"arn:aws:s3:::{bucket}"
queue_policy = {
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {"Service": "s3.amazonaws.com"},
            "Action": "sqs:SendMessage",
            "Resource": main_queue_arn,
            "Condition": {"ArnEquals": {"aws:SourceArn": source_arn}},
        }
    ],
}
redrive_policy = {"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "3"}
print(json.dumps({
    "Policy": json.dumps(queue_policy),
    "RedrivePolicy": json.dumps(redrive_policy),
}))
PY
)

awslocal sqs set-queue-attributes \
  --queue-url "$MAIN_QUEUE_URL" \
  --attributes "$QUEUE_ATTRIBUTES" >/dev/null

NOTIFICATION_CONFIG=$(python3 - "$MAIN_QUEUE_ARN" <<'PY'
import json
import sys

print(json.dumps({
    "QueueConfigurations": [
        {
            "QueueArn": sys.argv[1],
            "Events": ["s3:ObjectCreated:*"],
        }
    ]
}))
PY
)

awslocal s3api put-bucket-notification-configuration \
  --bucket "$BUCKET" \
  --notification-configuration "$NOTIFICATION_CONFIG" >/dev/null
