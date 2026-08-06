#!/usr/bin/env bash
# Verify Mihomo security gate after product patches are applied.
#
# Apply-only checks (git apply succeeded) are not enough: upstream can refactor
# so the patch still applies while no_ssh no longer excludes SSH outbound.
# This script checks gitlink pin, exact patch working tree, and real go deps.
#
# Usage:
#   ./scripts/verify-mihomo-gate.sh
#   ./scripts/verify-mihomo-gate.sh --skip-gitlink   # intentional remote bump
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASH="$ROOT/core/src/foss/golang/clash"
SUBMODULE_PATH="core/src/foss/golang/clash"

# Build tags must match product gate (see core/build.gradle.kts / patch).
# Intentionally hard-coded here — not read from Gradle — so the check cannot
# silently track a broken Gradle config.
TAGS_WITH_NO_SSH='foss,with_gvisor,cmfa,no_ssh'
TAGS_WITHOUT_NO_SSH='foss,with_gvisor,cmfa'
SSH_DEP_PATTERN='metacubex/ssh'

# Product ships Android binaries (golang-android), not the CI runner's linux/amd64.
# go list inherits GOOS from the host unless set — that misses android-only deps.
# Override with VERIFY_GOOS / VERIFY_GOARCH if needed; default is primary ABI.
VERIFY_GOOS="${VERIFY_GOOS:-android}"
VERIFY_GOARCH="${VERIFY_GOARCH:-arm64}"

SKIP_GITLINK=0
if [[ "${1:-}" == "--skip-gitlink" ]]; then
  SKIP_GITLINK=1
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--skip-gitlink]" >&2
  exit 2
fi

if [[ ! -d "$CLASH" ]]; then
  echo "error: mihomo submodule missing at $CLASH" >&2
  echo "run: git submodule update --init --recursive" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# A. Submodule HEAD matches parent-recorded gitlink (unless bump in progress).
# ---------------------------------------------------------------------------
check_gitlink() {
  local recorded actual
  recorded="$(git -C "$ROOT" ls-tree HEAD "$SUBMODULE_PATH" | awk '{print $3}')"
  if [[ -z "$recorded" ]]; then
    echo "error: cannot read gitlink for $SUBMODULE_PATH from HEAD" >&2
    exit 1
  fi
  actual="$(git -C "$CLASH" rev-parse HEAD)"
  if [[ "$recorded" != "$actual" ]]; then
    echo "error: submodule HEAD does not match parent gitlink" >&2
    echo "  path:     $SUBMODULE_PATH" >&2
    echo "  gitlink:  $recorded" >&2
    echo "  HEAD:     $actual" >&2
    echo "  someone moved the submodule checkout; reset to gitlink or update parent" >&2
    exit 1
  fi
  echo "ok: submodule HEAD matches gitlink ($actual)"
}

# ---------------------------------------------------------------------------
# B. Working tree == exactly product patch result (shared lists in apply script).
# ---------------------------------------------------------------------------
check_patch_tree() {
  "$ROOT/scripts/apply-mihomo-patches.sh" --verify
}

# ---------------------------------------------------------------------------
# C. no_ssh tag actually excludes metacubex/ssh; control without tag includes it.
#    Graph is evaluated for the Android product target, not the CI host OS.
# ---------------------------------------------------------------------------
go_list_deps() {
  local tags="$1"
  # Submodule is the module root. Force product GOOS/GOARCH so android-only
  # files/packages participate; host linux would miss them.
  (
    cd "$CLASH"
    export GOOS="$VERIFY_GOOS"
    export GOARCH="$VERIFY_GOARCH"
    go list -tags "$tags" -deps ./...
  )
}

check_ssh_deps() {
  local with_out without_out
  local target_desc="GOOS=$VERIFY_GOOS GOARCH=$VERIFY_GOARCH"

  if ! command -v go >/dev/null 2>&1; then
    echo "error: go not found in PATH (required for SSH gate dependency check)" >&2
    echo "  install Go or run after CI 'Setup Go'; refusing silent skip" >&2
    exit 1
  fi

  echo "check: dependency graph for $target_desc"

  if ! with_out="$(go_list_deps "$TAGS_WITH_NO_SSH" 2>&1)"; then
    echo "error: go list failed ($target_desc tags $TAGS_WITH_NO_SSH)" >&2
    printf '%s\n' "$with_out" >&2
    exit 1
  fi
  if grep -Fq "$SSH_DEP_PATTERN" <<<"$with_out"; then
    echo "error: security gate failed: $SSH_DEP_PATTERN still in deps ($target_desc tags $TAGS_WITH_NO_SSH)" >&2
    grep -F "$SSH_DEP_PATTERN" <<<"$with_out" >&2 || true
    echo "  no_ssh did not exclude SSH outbound; patch or upstream wiring broken" >&2
    exit 1
  fi
  echo "ok: no $SSH_DEP_PATTERN in deps ($target_desc tags $TAGS_WITH_NO_SSH)"

  if ! without_out="$(go_list_deps "$TAGS_WITHOUT_NO_SSH" 2>&1)"; then
    echo "error: go list failed ($target_desc tags $TAGS_WITHOUT_NO_SSH)" >&2
    printf '%s\n' "$without_out" >&2
    exit 1
  fi
  if ! grep -Fq "$SSH_DEP_PATTERN" <<<"$without_out"; then
    echo "error: control check failed: $SSH_DEP_PATTERN absent even without no_ssh" >&2
    echo "  target: $target_desc" >&2
    echo "  tags: $TAGS_WITHOUT_NO_SSH" >&2
    echo "  upstream likely moved SSH off this import path; gate check is vacuous" >&2
    exit 1
  fi
  echo "ok: control — $SSH_DEP_PATTERN present without no_ssh ($target_desc)"
}

if ((SKIP_GITLINK)); then
  echo "skip: gitlink check (--skip-gitlink; intentional remote bump)"
else
  check_gitlink
fi
check_patch_tree
check_ssh_deps
echo "ok: mihomo security gate verified"
