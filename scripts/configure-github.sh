#!/bin/sh
set -eu
# Run only after the first CI run on main has reported verify successfully.
repo=dpyorang/logscope
gh auth status
gh api --method PUT "repos/$repo/branches/main/protection" \
  --input .github/branch-protection.json
gh api "repos/$repo/branches/main/protection"
