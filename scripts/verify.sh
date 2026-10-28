#!/bin/sh
set -eu
# Docker tags are shared across worktrees; each verification owns its images.
verify_scope="logscope-verify-$(date +%s)-$$"
export LOGSCOPE_VERIFY_IMAGE="$verify_scope:backend"
export LOGSCOPE_FRONTEND_VERIFY_IMAGE="$verify_scope:frontend"
export LOGSCOPE_SAMPLE_VERIFY_IMAGE="$verify_scope:sample"
export LOGSCOPE_SEARCH_VERIFY_IMAGE="$verify_scope:search"
cleanup_images() {
  docker image rm "$LOGSCOPE_VERIFY_IMAGE" "$LOGSCOPE_FRONTEND_VERIFY_IMAGE" "$LOGSCOPE_SAMPLE_VERIFY_IMAGE" "$LOGSCOPE_SEARCH_VERIFY_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup_images EXIT HUP INT TERM
./scripts/check-secrets.sh
./gradlew --no-daemon --console=plain clean build
./scripts/verify-frontend.sh
docker compose --env-file .env.example config --quiet
docker build --tag "$LOGSCOPE_SEARCH_VERIFY_IMAGE" ./elasticsearch
docker build --tag "$LOGSCOPE_VERIFY_IMAGE" .
node scripts/verify-search.mjs
docker build --tag "$LOGSCOPE_FRONTEND_VERIFY_IMAGE" ./frontend
./scripts/check-frontend-image.sh
node scripts/verify-log-view.mjs
docker compose --env-file .env.example --profile samples config --quiet
docker build --tag "$LOGSCOPE_SAMPLE_VERIFY_IMAGE" --file sample-was/Dockerfile .
docker run --rm --volume "$PWD/collectors/vector.yaml:/etc/vector/vector.yaml:ro" timberio/vector:0.60.0-debian validate --no-environment /etc/vector/vector.yaml
node scripts/verify-sample-pipeline.mjs
