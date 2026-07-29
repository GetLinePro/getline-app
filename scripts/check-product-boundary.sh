#!/usr/bin/env bash
# Gate: product GetLine surfaces must not depend on CMFA core/service types or
# identifiers directly. CMFA lives only behind pro.getline.vpn.cmfa adapters.
#
# common.* is allowed. Legacy Activities outside the checked paths are out of
# scope. Not wired into Gradle — run manually or from CI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Product-facing files only (see handoff non-goals for legacy Activities).
# getlineui: product UI module (slice 4). design/model and design/view no longer
# hold GetLine* after the move.
mapfile -t APP_TARGETS < <(
  {
    printf '%s\n' \
      "app/src/main/java/pro/getline/vpn/GetLineHomeActivity.kt" \
      "app/src/main/java/pro/getline/vpn/GetLineOnboardingActivity.kt" \
      "app/src/main/java/pro/getline/vpn/QrScannerActivity.kt"
    # shellcheck disable=SC2086
    find app/src/main/java/pro/getline/vpn/getline -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true
    # shellcheck disable=SC2086
    find app/src/main/java/pro/getline/vpn/product -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true
  } | while read -r p; do
    [[ -e "$p" ]] && printf '%s\n' "$p"
  done
)

# Require the product UI module sources to be present; empty find must not
# silently pass while only app paths keep TARGETS nonempty.
mapfile -t GETLINEUI_TARGETS < <(
  {
    # All production source roots of :getlineui (main/foss/flavor/debug).
    # shellcheck disable=SC2086
    find getlineui/src \
      \( -path '*/test/*' -o -path '*/androidTest/*' \) -prune -o \
      -type f \( -name '*.kt' -o -name '*.java' \) -print 2>/dev/null || true
  } | while read -r p; do
    [[ -e "$p" ]] && printf '%s\n' "$p"
  done
)

if ((${#APP_TARGETS[@]} == 0)); then
  echo "error: no product boundary targets found" >&2
  exit 1
fi

if ((${#GETLINEUI_TARGETS[@]} == 0)); then
  echo "error: no getlineui product boundary targets found" >&2
  exit 1
fi

TARGETS=("${APP_TARGETS[@]}" "${GETLINEUI_TARGETS[@]}")

violations=0

# 1) Direct core/service imports
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  echo "import-violation: $line"
  violations=$((violations + 1))
done < <(
  # core restored to upstream package (slice 7a); service stays pro.getline.vpn until 7b.
  grep -nE '^import (pro\.getline\.vpn\.(core|service)|com\.github\.kr328\.clash\.(core|service))\.' "${TARGETS[@]}" 2>/dev/null || true
)

# 2) Bare CMFA identifiers (catches type leakage without import).
# Include lowercase proxySort — Kotlin infers ProxySort from uiStore.proxySort
# with no import; matching only ProxySort misses the exact AC regression.
# UiStore after slice 4: product must use GetLineUiStore only.
ID_PATTERN='\b(ProxySort|proxySort|ProxyGroup|IClashManager|IProfileManager|TunnelState|withClash|trafficTotal|ClashException|BaseActivity|FetchStatus|UiStore)\b'
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  echo "identifier-violation: $line"
  violations=$((violations + 1))
done < <(
  grep -nE "$ID_PATTERN" "${TARGETS[@]}" 2>/dev/null || true
)

if ((violations > 0)); then
  echo "product-boundary: $violations violation(s)" >&2
  exit 1
fi

echo "product-boundary: ok"
