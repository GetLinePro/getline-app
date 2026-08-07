#!/usr/bin/env bash
# GL-22 / #76: Advanced (legacy CMFA) activities must stay stripped from release.
#
# tools:node="remove" is a silent no-op when the target activity is missing from
# the main manifest (rename / upstream move). This gate turns that silent miss
# into a hard failure, and optionally proves the merged release manifest no
# longer declares those activities.
#
# Checks:
#   1) Source consistency (always):
#      every android:name under tools:node="remove" in
#      app/src/release/AndroidManifest.xml still appears in
#      app/src/main/AndroidManifest.xml.
#   2) Merged release (when available / required):
#      none of those short class names appear as an activity in the merged
#      alphaProdRelease manifest.
#   3) Merged debug (when available):
#      each of those names is still present — proves we did not over-remove.
#
# Usage (repo root):
#   ./scripts/check-advanced-release-gate.sh
#   ./scripts/check-advanced-release-gate.sh --merged path/to/AndroidManifest.xml
#   ./scripts/check-advanced-release-gate.sh --require-merged
#
# --merged overrides only the release merged-manifest path. Debug still uses
# the default alphaProdDebug process*Manifest output (see DEFAULT_MERGED_DEBUG).
#
# Produce the default merged paths with:
#   ./gradlew :app:processAlphaProdReleaseMainManifest \
#             :app:processAlphaProdDebugMainManifest
#
# Parser is POSIX awk (mawk + gawk). Do not use gawk-only match(..., arr).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RELEASE_SRC="app/src/release/AndroidManifest.xml"
MAIN_SRC="app/src/main/AndroidManifest.xml"
DEFAULT_MERGED_RELEASE="app/build/intermediates/merged_manifest/alphaProdRelease/processAlphaProdReleaseMainManifest/AndroidManifest.xml"
DEFAULT_MERGED_DEBUG="app/build/intermediates/merged_manifest/alphaProdDebug/processAlphaProdDebugMainManifest/AndroidManifest.xml"

MERGED_RELEASE=""
REQUIRE_MERGED=0

usage() {
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

while (($# > 0)); do
  case "$1" in
    --merged)
      [[ $# -ge 2 ]] || usage
      MERGED_RELEASE="$2"
      shift 2
      ;;
    --require-merged)
      REQUIRE_MERGED=1
      shift
      ;;
    -h | --help)
      usage
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage
      ;;
  esac
done

if [[ ! -f "$RELEASE_SRC" ]]; then
  echo "error: missing $RELEASE_SRC (GL-22 release strip list)" >&2
  exit 1
fi
if [[ ! -f "$MAIN_SRC" ]]; then
  echo "error: missing $MAIN_SRC" >&2
  exit 1
fi

# Short class name: ".ProfilesActivity" / "com.foo.ProfilesActivity" → ProfilesActivity
short_name() {
  local n="$1"
  n="${n%.}"
  n="${n##*.}"
  printf '%s\n' "$n"
}

# Activity android:name values that the release overlay removes.
# Only accept tools:node="remove" targets so a future non-remove entry is ignored.
#
# Activity tags are usually split across lines:
#   <activity
#       android:name=".Foo"
#       tools:node="remove" />
# Match bare <activity (end of line) as well as same-line forms.
# POSIX match()+substr (RSTART/RLENGTH): works under mawk and gawk.
# gawk-only match($0, /.../, arr) is intentionally avoided.
#
# Capture awk status separately: process substitution does not fail the parent
# on a non-zero awk exit, so a syntax error would otherwise look like an empty
# strip list.
REMOVE_NAMES_FILE="$(mktemp)"
trap 'rm -f "$REMOVE_NAMES_FILE"' EXIT
set +e
awk '
  /<activity([[:space:]>/]|$)/ { in_act = 1; name = ""; remove = 0 }
  in_act && match($0, /android:name="[^"]+"/) {
    # 14 = length of android:name=" ; RLENGTH-15 drops prefix and closing quote.
    name = substr($0, RSTART + 14, RLENGTH - 15)
  }
  in_act && /tools:node="remove"/ { remove = 1 }
  in_act && /(\/>|<\/activity>)/ {
    if (remove && name != "") print name
    in_act = 0
  }
' "$RELEASE_SRC" >"$REMOVE_NAMES_FILE"
awk_status=$?
set -e
if ((awk_status != 0)); then
  echo "error: failed to parse $RELEASE_SRC (awk exit $awk_status)" >&2
  echo "       parser must stay POSIX awk (mawk + gawk); no gawk-only match(arr)." >&2
  exit 1
fi
mapfile -t REMOVE_NAMES <"$REMOVE_NAMES_FILE"

if ((${#REMOVE_NAMES[@]} == 0)); then
  echo "error: $RELEASE_SRC declares no tools:node=\"remove\" activities" >&2
  echo "       empty strip list is a silent no-op; restore the GL-22 list." >&2
  exit 1
fi

violations=0
source_violations=0
declare -a SHORT_NAMES=()

for raw in "${REMOVE_NAMES[@]}"; do
  short="$(short_name "$raw")"
  if [[ -z "$short" ]]; then
    echo "error: empty activity name in $RELEASE_SRC" >&2
    violations=$((violations + 1))
    source_violations=$((source_violations + 1))
    continue
  fi
  SHORT_NAMES+=("$short")

  # Main uses relative ".Foo"; tolerate FQCN too.
  if ! grep -qE "android:name=\"([^\"]*\\.)?${short}\"" "$MAIN_SRC"; then
    echo "error: release removes .${short}, but it is absent from $MAIN_SRC" >&2
    echo "       tools:node=\"remove\" is a silent no-op for missing nodes." >&2
    violations=$((violations + 1))
    source_violations=$((source_violations + 1))
  fi
done

# Deduplicate for reporting / merged checks (stable order preserved by first seen).
mapfile -t SHORT_NAMES < <(printf '%s\n' "${SHORT_NAMES[@]}" | awk 'NF && !seen[$0]++')

if ((source_violations == 0)); then
  echo "OK   source strip list: ${#SHORT_NAMES[@]} activities in $RELEASE_SRC present in $MAIN_SRC"
fi

if [[ -z "$MERGED_RELEASE" ]]; then
  MERGED_RELEASE="$DEFAULT_MERGED_RELEASE"
fi

check_merged_release() {
  local merged="$1"
  local short
  local found=0
  for short in "${SHORT_NAMES[@]}"; do
    if grep -qE "android:name=\"([^\"]*\\.)?${short}\"" "$merged"; then
      echo "error: release merged manifest still declares ${short}" >&2
      echo "       file: $merged" >&2
      violations=$((violations + 1))
      found=1
    fi
  done
  if ((found == 0)); then
    echo "OK   release merged manifest has none of ${#SHORT_NAMES[@]} stripped activities"
    echo "     ($merged)"
  fi
}

check_merged_debug() {
  local merged="$1"
  local short
  local missing=0
  for short in "${SHORT_NAMES[@]}"; do
    if ! grep -qE "android:name=\"([^\"]*\\.)?${short}\"" "$merged"; then
      echo "error: debug merged manifest missing ${short} (over-stripped?)" >&2
      echo "       file: $merged" >&2
      violations=$((violations + 1))
      missing=1
    fi
  done
  if ((missing == 0)); then
    echo "OK   debug merged manifest still declares all ${#SHORT_NAMES[@]} Advanced activities"
    echo "     ($merged)"
  fi
}

if [[ -f "$MERGED_RELEASE" ]]; then
  check_merged_release "$MERGED_RELEASE"
elif ((REQUIRE_MERGED)); then
  echo "error: --require-merged set but merged release manifest not found:" >&2
  echo "       $MERGED_RELEASE" >&2
  echo "       run: ./gradlew :app:processAlphaProdReleaseMainManifest" >&2
  exit 1
else
  echo "skip merged release check (not built; pass --require-merged in CI after process*Manifest)"
fi

if [[ -f "$DEFAULT_MERGED_DEBUG" ]]; then
  check_merged_debug "$DEFAULT_MERGED_DEBUG"
elif ((REQUIRE_MERGED)); then
  # Debug presence is part of the acceptance matrix for GL-22; require it
  # whenever CI demands a real merge check.
  echo "error: --require-merged set but merged debug manifest not found:" >&2
  echo "       $DEFAULT_MERGED_DEBUG" >&2
  echo "       run: ./gradlew :app:processAlphaProdDebugMainManifest" >&2
  exit 1
fi

if ((violations > 0)); then
  echo "advanced-release-gate: FAIL ($violations violation(s))" >&2
  exit 1
fi

echo "advanced-release-gate: ok"
