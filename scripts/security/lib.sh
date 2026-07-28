#!/usr/bin/env bash
# Shared helpers for local/CI security scans.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SECURITY_DIR="$REPO_ROOT/security"
BASELINE_DIR="$SECURITY_DIR/baselines"
REPORT_DIR="${SECURITY_REPORT_DIR:-$SECURITY_DIR/reports}"

OSV_IMAGE="${OSV_IMAGE:-ghcr.io/google/osv-scanner:v2.2.3}"
SEMGREP_IMAGE="${SEMGREP_IMAGE:-semgrep/semgrep:1.128.1}"

mkdir -p "$REPORT_DIR"

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run security scanners" >&2
    exit 2
  fi
}

normalize_path() {
  local p="$1"
  p="${p#/src/}"
  p="${p#/}"
  printf '%s' "$p"
}
