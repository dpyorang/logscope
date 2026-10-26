#!/bin/sh
set -eu
./scripts/check-secrets.sh
./gradlew --no-daemon --console=plain clean build
docker compose --env-file .env.example config --quiet
docker build --tag logscope:verify .
