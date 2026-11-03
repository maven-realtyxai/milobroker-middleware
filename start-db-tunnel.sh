#!/usr/bin/env bash
set -euo pipefail

TARGET_INSTANCE="${SSM_TARGET_INSTANCE_ID:-i-06d94537d79a661d8}"
AWS_REGION="${AWS_REGION:-ap-southeast-2}"
REMOTE_PORT="${REMOTE_PORT:-5432}"
LOCAL_PORT="${LOCAL_PORT:-5433}"

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI was not found. Install AWS CLI v2 and try again." >&2
  exit 1
fi

AWS_ARGS=(--region "$AWS_REGION")
if [[ -n "${AWS_PROFILE:-}" ]]; then
  AWS_ARGS+=(--profile "$AWS_PROFILE")
fi

if ! aws sts get-caller-identity "${AWS_ARGS[@]}" >/dev/null 2>&1; then
  echo "AWS credentials are missing or expired; starting AWS SSO login..."
  if [[ -n "${AWS_PROFILE:-}" ]]; then
    aws sso login --profile "$AWS_PROFILE"
  else
    aws sso login
  fi

  if ! aws sts get-caller-identity "${AWS_ARGS[@]}" >/dev/null; then
    echo "AWS login did not provide usable credentials. Check your AWS SSO profile." >&2
    exit 1
  fi
fi

echo "Starting database tunnel: localhost:${LOCAL_PORT} -> ${TARGET_INSTANCE}:${REMOTE_PORT} (${AWS_REGION})"
exec aws ssm start-session \
  "${AWS_ARGS[@]}" \
  --target "$TARGET_INSTANCE" \
  --document-name AWS-StartPortForwardingSession \
  --parameters "portNumber=${REMOTE_PORT},localPortNumber=${LOCAL_PORT}"
