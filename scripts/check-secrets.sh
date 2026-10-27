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
scan_dir=$(mktemp -d)
trap 'rm -rf "$scan_dir"' EXIT HUP INT TERM
patch="$scan_dir/staged.patch"
history="$scan_dir/history.patch"
git diff --cached --no-ext-diff --no-textconv --no-color > "$patch"
docker run --rm --network none -i "$image" stdin --redact --no-banner < "$patch"
# Resolve worktree metadata on the host instead of mounting its .git pointer into
# Docker. Materialize the complete log before scanning so Git errors cannot be
# hidden by a pipeline or by Gitleaks reporting success after scanning no commits.
if [ "$(git rev-parse --is-shallow-repository)" != false ]; then
  printf 'Full Git history is required; shallow repositories are not supported.\n' >&2
  exit 1
fi
commit_count=$(git rev-list --all --count)
if [ "$commit_count" -eq 0 ]; then
  printf 'No commits available for the full-history secret scan.\n' >&2
  exit 1
fi
git log --all --full-history --root --diff-merges=separate --no-renames \
  --patch --no-ext-diff --no-textconv \
  --no-color --format=fuller > "$history"
printf 'Scanning full Git history: %s commits\n' "$commit_count"
docker run --rm --network none -i "$image" stdin --redact --no-banner < "$history"
