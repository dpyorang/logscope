#!/bin/sh
set -eu
./scripts/check-secrets.sh
./mvnw -B -ntp clean verify
docker compose --env-file .env.example config --quiet
docker build --tag logscope:verify .
