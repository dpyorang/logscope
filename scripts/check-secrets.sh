#!/bin/sh
set -eu

# Never print secret values; Gitleaks findings are fully redacted.
root=$(git rev-parse --show-toplevel)
cd "$root"
blocked=$(git ls-files | awk '
  /(^|\/)\.env($|\.)/ && $0 !~ /(^|\/)\.env\.example$/ { print; next }
  /(^|\/)(secrets|backups|\.aws|\.ssh)\// { print; next }
  /\.(pem|key|p12|pfx|jks|keystore|dump)$/ { print }
')
if [ -n "$blocked" ]; then
  printf 'Secret or backup files must not be tracked:\n%s\n' "$blocked" >&2
  exit 1
fi

image=ghcr.io/gitleaks/gitleaks:v8.30.1
# Pull before disabling container networking. Repository data stays on this machine.
docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
patch=$(mktemp)
trap 'rm -f "$patch"' EXIT HUP INT TERM
git diff --cached --no-ext-diff --no-color > "$patch"
docker run --rm --network none -i "$image" stdin --redact --no-banner < "$patch"
docker run --rm --network none \
  -v "$root/.git:/repo/.git:ro" -w /repo \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo \
  "$image" git --log-opts=--all --redact --no-banner .
