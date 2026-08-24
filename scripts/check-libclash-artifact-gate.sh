#!/usr/bin/env bash
# Production artifact gate for shipped libclash.so (issue #20 / residual R3).
#
# go list is not enough: this checks the binary CI just built.
# go version -m proves modules and build settings, not a package inventory.
# It does not claim golang.org/x/net/http/httpproxy was linked.
#
# Usage:
#   ./scripts/check-libclash-artifact-gate.sh path/to/libclash.so [...]
#
# CI must pass the files produced by this run. No glob, no default paths:
# a missing just-built .so must fail, not fall through to a stale artifact.
set -euo pipefail

STUB_MARKER='ssh outbound is disabled (build tag no_ssh; not a supported GetLine product path)'
XNET_FLOOR='v0.36.0' # CVE-2025-22870 fixed in golang.org/x/net >= 0.36.0
FORBIDDEN_STRINGS=(
  'github.com/metacubex/ssh'
  'golang.org/x/crypto/ssh'
)

usage() {
  echo "usage: $0 path/to/libclash.so [...]" >&2
  echo "  pass the libclash.so files this build produced; do not glob a cache" >&2
  exit 2
}

# Compare Go module versions. Not string >.
# Semver / Go: X.Y.Z-pre < X.Y.Z. A pseudo-version v0.36.0-0.timestamp is a
# commit that predates the v0.36.0 tag — not the CVE fix. Floor is a release.
# Higher core (v0.36.1-0.timestamp) still passes.
# $1 >= $2 → 0.
mod_version_ge() {
  local got="${1#v}"
  local floor="${2#v}"
  local g_maj g_min g_pat g_sep f_maj f_min f_pat
  if [[ ! "$got" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([+-]|$) ]]; then
    return 1
  fi
  g_maj="${BASH_REMATCH[1]}"
  g_min="${BASH_REMATCH[2]}"
  g_pat="${BASH_REMATCH[3]}"
  g_sep="${BASH_REMATCH[4]}"
  if [[ ! "$floor" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([+-]|$) ]]; then
    return 1
  fi
  f_maj="${BASH_REMATCH[1]}"
  f_min="${BASH_REMATCH[2]}"
  f_pat="${BASH_REMATCH[3]}"
  if ((g_maj != f_maj)); then
    ((g_maj > f_maj))
    return
  fi
  if ((g_min != f_min)); then
    ((g_min > f_min))
    return
  fi
  if ((g_pat != f_pat)); then
    ((g_pat > f_pat))
    return
  fi
  # Same major.minor.patch as the release floor: pre-release / pseudo is below.
  [[ "$g_sep" != "-" ]]
}

if (($# == 0)) || [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
fi

if ! command -v go >/dev/null 2>&1; then
  echo "error: go not found in PATH (required to read libclash.so buildinfo)" >&2
  exit 1
fi
if ! command -v strings >/dev/null 2>&1; then
  echo "error: strings not found in PATH (required for stub/SSH path scan)" >&2
  exit 1
fi

GATE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/libclash-artifact.XXXXXX")"
trap 'rm -rf "$GATE_TMP"' EXIT

check_one() {
  local so="$1"
  local info="$GATE_TMP/buildinfo"
  local str="$GATE_TMP/strings"
  local err="$GATE_TMP/err"
  local tags xnet_ver needle

  if [[ ! -e "$so" ]]; then
    echo "error: missing libclash.so: $so" >&2
    echo "  the native build did not produce this file; refusing a stale glob" >&2
    return 1
  fi
  if [[ ! -f "$so" ]]; then
    echo "error: not a regular file: $so" >&2
    return 1
  fi
  if [[ ! -s "$so" ]]; then
    echo "error: empty libclash.so: $so" >&2
    return 1
  fi

  if ! go version -m "$so" >"$info" 2>"$err"; then
    echo "error: go version -m failed: $so" >&2
    cat "$err" >&2
    cat "$info" >&2
    return 1
  fi
  # go version -m exits 0 on some non-Go files and writes nothing.
  if ! awk -F '\t' '$2 == "build" { found = 1 } END { exit found ? 0 : 1 }' "$info"; then
    echo "error: go version -m produced no build settings (not a Go binary?): $so" >&2
    return 1
  fi

  tags="$(awk -F '\t' '
    $2 == "build" && $3 ~ /^-tags=/ {
      n = split(substr($3, 7), a, ",")
      for (i = 1; i <= n; i++) if (a[i] == "no_ssh") found = 1
    }
    END { if (found) print "no_ssh" }
  ' "$info")"
  if [[ "$tags" != "no_ssh" ]]; then
    echo "error: build settings do not contain tag no_ssh: $so" >&2
    awk -F '\t' '$2 == "build" { print }' "$info" >&2 || true
    return 1
  fi

  ssh_deps="$(awk -F '\t' '
    $2 == "dep" && ($3 == "github.com/metacubex/ssh" || index($3, "github.com/metacubex/ssh/") == 1) {
      print
    }
  ' "$info")"
  if [[ -n "$ssh_deps" ]]; then
    echo "error: github.com/metacubex/ssh present in buildinfo: $so" >&2
    printf '%s\n' "$ssh_deps" >&2
    return 1
  fi

  xnet_ver="$(awk -F '\t' '
    $2 == "dep" && $3 == "golang.org/x/net" { print $4; n++ }
    END { if (n != 1) exit 1 }
  ' "$info")" || {
    echo "error: expected exactly one dep golang.org/x/net in buildinfo: $so" >&2
    awk -F '\t' '$2 == "dep" && $3 ~ /golang.org\/x\/net/ { print }' "$info" >&2 || true
    return 1
  }
  if ! mod_version_ge "$xnet_ver" "$XNET_FLOOR"; then
    echo "error: golang.org/x/net $xnet_ver is below $XNET_FLOOR (CVE-2025-22870): $so" >&2
    return 1
  fi

  # Dump once. grep -q + pipefail + strings can SIGPIPE on a 47MB .so.
  strings -a "$so" >"$str"

  for needle in "${FORBIDDEN_STRINGS[@]}"; do
    if grep -F -e "$needle" "$str" >/dev/null; then
      echo "error: forbidden path in strings: $needle ($so)" >&2
      grep -F -e "$needle" "$str" >&2 || true
      return 1
    fi
  done

  if ! grep -F -e "$STUB_MARKER" "$str" >/dev/null; then
    echo "error: missing no_ssh stub marker in strings: $so" >&2
    echo "  expected: $STUB_MARKER" >&2
    echo "  without the marker a negative SSH check is vacuous" >&2
    return 1
  fi

  echo "ok: $so (no_ssh, no metacubex/ssh, x/net $xnet_ver, stub present)"
}

failed=0
for so in "$@"; do
  if [[ "$so" == -* ]]; then
    echo "error: unexpected flag: $so" >&2
    usage
  fi
  if ! check_one "$so"; then
    failed=1
  fi
done
if ((failed)); then
  exit 1
fi
echo "ok: libclash artifact gate verified ($# file(s))"
