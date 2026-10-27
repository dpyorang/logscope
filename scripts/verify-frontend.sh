#!/bin/sh
set -eu
cd "$(dirname "$0")/../frontend"
export NEXT_TELEMETRY_DISABLED=1
npm run check:runtime
npm ci --no-audit --no-fund
npm run typecheck
npm run lint
npm run format:check
npm run build
npx --no-install playwright install chromium
npm run test:e2e
