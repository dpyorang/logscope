#!/bin/sh
set -eu
./scripts/check-secrets.sh
./gradlew --no-daemon --console=plain clean build
./scripts/verify-frontend.sh
docker compose --env-file .env.example config --quiet
docker build --tag logscope:verify .
docker build --tag logscope-frontend:verify ./frontend
./scripts/check-frontend-image.sh
node scripts/verify-log-view.mjs
