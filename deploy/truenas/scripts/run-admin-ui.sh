#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."
docker compose up -d
printf '%s\n' "Nibbl backend is starting."
printf '%s\n' "Admin UI: https://api.nibbl.z2hs.au/admin.html"
printf '%s\n' "Health:   https://api.nibbl.z2hs.au/api/health"
