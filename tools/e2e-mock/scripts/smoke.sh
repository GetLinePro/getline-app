#!/usr/bin/env bash
# Backward-compatible entrypoint → API contract smoke.
# Prefer: ./scripts/smoke-api.sh
#
# This is NOT Android S0/S1 smoke (Auth Tab / DAL / session / import).
# See: ./scripts/watch-android-smoke.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/smoke-api.sh" "$@"
