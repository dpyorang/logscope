#!/bin/sh
set -eu
container=$(docker run --detach --publish 127.0.0.1::3000 "${LOGSCOPE_FRONTEND_VERIFY_IMAGE:-logscope-frontend:verify}")
trap 'docker rm --force "$container" >/dev/null' EXIT HUP INT TERM
attempt=0
while [ "$attempt" -lt 30 ]; do
  state=$(docker inspect --format '{{.State.Health.Status}}' "$container")
  if [ "$state" = healthy ]; then
    docker exec "$container" node -e "fetch('http://127.0.0.1:3000/').then(async r => { if (!r.ok || !(await r.text()).includes('로그 탐색')) process.exit(1); }).catch(() => process.exit(1))"
    printf 'Frontend image: healthy; SSR page available\n'
    exit 0
  fi
  if [ "$state" = unhealthy ]; then
    printf 'Frontend image health check failed\n' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 1
done
printf 'Frontend image readiness timed out\n' >&2
exit 1
