#!/bin/bash
set -e

BUCKET_NAME="${S3_BUCKET_NAME:-ieum-voice-storage-local}"
REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"
EPHEMERAL_PREFIX="${S3_EPHEMERAL_PREFIX:-audio/ephemeral-30d/}"

echo "[LocalStack Init] Creating S3 bucket: ${BUCKET_NAME} in region ${REGION}..."

# 1. 버킷 생성 (멱등성 보장: 이미 존재하면 무시, us-east-1 리전 예외 대응)
if ! awslocal s3api head-bucket --bucket "${BUCKET_NAME}" 2>/dev/null; then
  if [ "${REGION}" = "us-east-1" ]; then
    awslocal s3api create-bucket --bucket "${BUCKET_NAME}" --region "${REGION}"
  else
    awslocal s3api create-bucket \
      --bucket "${BUCKET_NAME}" \
      --region "${REGION}" \
      --create-bucket-configuration LocationConstraint="${REGION}"
  fi
fi

# 2. 퍼블릭 액세스 완전 차단 (Presigned URL 전용 접근 강제)
awslocal s3api put-public-access-block \
  --bucket "${BUCKET_NAME}" \
  --public-access-block-configuration "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

# 3. 무료 플랜 30일 보관 후 자동 삭제(31일 차 물리 만료) Lifecycle 규칙 멱등(Upsert) 적용
awslocal s3api put-bucket-lifecycle-configuration \
  --bucket "${BUCKET_NAME}" \
  --lifecycle-configuration "{
    \"Rules\": [
      {
        \"ID\": \"rule-expire-free-audio-30d\",
        \"Status\": \"Enabled\",
        \"Filter\": {
          \"Prefix\": \"${EPHEMERAL_PREFIX}\"
        },
        \"Expiration\": {
          \"Days\": 31
        }
      }
    ]
  }"

echo "[LocalStack Init] Bucket '${BUCKET_NAME}' and lifecycle rule 'rule-expire-free-audio-30d' configured successfully."
