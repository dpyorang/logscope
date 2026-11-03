#!/bin/sh
set -eu
root=$(git rev-parse --show-toplevel)
cd "$root"
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
for name in DB_USERNAME DB_PASSWORD MYSQL_ROOT_PASSWORD; do
  printf 'example-only\n' > "$fixture/$name"
done
image=caddy:2.11.7
docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
printf 'demo ' > "$fixture/WEB_AUTH"
docker run --rm --network none "$image" caddy hash-password --plaintext example-only >> "$fixture/WEB_AUTH"
export DEMO_SECRET_DIR="$fixture" DEMO_HOST=demo.example.com
export APP_IMAGE=example/backend:verified FRONTEND_IMAGE=example/frontend:verified
export SAMPLE_IMAGE=example/sample:verified SEARCH_IMAGE=example/search:verified
docker compose -f compose.demo.yaml config --quiet
docker run --rm --network none -e DEMO_HOST \
  --volume "$PWD/deploy/demo/Caddyfile:/etc/caddy/Caddyfile:ro" \
  --volume "$fixture/WEB_AUTH:/run/secrets/WEB_AUTH:ro" \
  "$image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
