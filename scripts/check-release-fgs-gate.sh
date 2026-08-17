#!/usr/bin/env bash
# GL-12 / #39: release must not ship specialUse FGS.
#
# GetLine-owned foreground lifetime is TunService (systemExempted).
# Background subscription refresh does not use foreground execution.
# androidx.work SystemForegroundService without foregroundServiceType is
# library infrastructure and is not a violation.
# Do not set enable_system_foreground_service_default to false.
#
# Checks:
#   1) Source (always):
#      service main: SYSTEM_EXEMPTED permission, TunService systemExempted,
#      no specialUse / PROPERTY_SPECIAL_USE_FGS_SUBTYPE.
#      app main: no LogcatService, specialUse, PROPERTY_SPECIAL_USE_FGS_SUBTYPE,
#      or FOREGROUND_SERVICE_SPECIAL_USE.
#      app debug: LogcatService with specialUse, subtype, and SPECIAL_USE
#      permission. Release overlay must not mention LogcatService.
#      ProfileRefreshWorker does not call setForeground / setForegroundAsync.
#      no resource sets enable_system_foreground_service_default to false.
#   2) Merged release (when available / required):
#      no specialUse, no FOREGROUND_SERVICE_SPECIAL_USE, no
#      PROPERTY_SPECIAL_USE_FGS_SUBTYPE. Exactly one GetLine-owned service
#      with a foregroundServiceType: TunService = systemExempted.
#      SystemForegroundService without a type is allowed.
#   3) Merged debug (when available / required):
#      LogcatService still specialUse; FOREGROUND_SERVICE_SPECIAL_USE present;
#      TunService still systemExempted.
#
# Usage (repo root):
#   ./scripts/check-release-fgs-gate.sh
#   ./scripts/check-release-fgs-gate.sh --merged path/to/AndroidManifest.xml
#   ./scripts/check-release-fgs-gate.sh --require-merged
#
# --merged overrides only the release merged-manifest path. Debug still uses
# DEFAULT_MERGED_DEBUG. Produce the default paths with:
#   ./gradlew :app:processAlphaProdReleaseMainManifest \
#             :app:processAlphaProdDebugMainManifest
#
# Parser is POSIX awk (mawk + gawk). Do not use gawk-only match(..., arr).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SERVICE_SRC="service/src/main/AndroidManifest.xml"
APP_MAIN_SRC="app/src/main/AndroidManifest.xml"
APP_DEBUG_SRC="app/src/debug/AndroidManifest.xml"
APP_RELEASE_SRC="app/src/release/AndroidManifest.xml"
REFRESH_DIR="app/src/main/java/pro/getline/vpn/getline/refresh"
DEFAULT_MERGED_RELEASE="app/build/intermediates/merged_manifest/alphaProdRelease/processAlphaProdReleaseMainManifest/AndroidManifest.xml"
DEFAULT_MERGED_DEBUG="app/build/intermediates/merged_manifest/alphaProdDebug/processAlphaProdDebugMainManifest/AndroidManifest.xml"
DEFAULT_MERGED_META_RELEASE="app/build/intermediates/merged_manifest/metaProdRelease/processMetaProdReleaseMainManifest/AndroidManifest.xml"

MERGED_RELEASE=""
REQUIRE_MERGED=0
violations=0

usage() {
  sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'
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

fail() {
  echo "error: $*" >&2
  violations=$((violations + 1))
}

require_file() {
  if [[ ! -f "$1" ]]; then
    fail "missing $1"
    return 1
  fi
}

has_perm() {
  local file="$1" perm="$2"
  grep -qE "android:name=\"android.permission.${perm}\"" "$file"
}

# Print "name<TAB>type" for each <service>. type is empty when unset.
# Only read attributes on the opening <service> tag so a nested <property
# android:name="..."> cannot overwrite the service name or end the block
# on its own "/>".
# 14 = length of android:name=" ; 31 = length of android:foregroundServiceType="
parse_services() {
  local file="$1"
  awk '
    /<service([[:space:]>/]|$)/ { in_svc = 1; in_open = 1; name = ""; ftype = "" }
    in_svc && in_open && match($0, /android:name="[^"]+"/) {
      name = substr($0, RSTART + 14, RLENGTH - 15)
    }
    in_svc && in_open && match($0, /android:foregroundServiceType="[^"]+"/) {
      ftype = substr($0, RSTART + 31, RLENGTH - 32)
    }
    in_svc && in_open && />/ {
      if ($0 ~ /\/>/) {
        if (name != "") printf "%s\t%s\n", name, ftype
        in_svc = 0
      }
      in_open = 0
    }
    in_svc && /<\/service>/ {
      if (name != "") printf "%s\t%s\n", name, ftype
      in_svc = 0
      in_open = 0
    }
  ' "$file"
}

is_getline_owned() {
  case "$1" in
    .*|com.github.kr328.clash*|pro.getline.*) return 0 ;;
    *) return 1 ;;
  esac
}

is_tun_service() {
  case "$1" in
    .TunService|com.github.kr328.clash.service.TunService) return 0 ;;
    *) return 1 ;;
  esac
}

is_logcat_service() {
  case "$1" in
    .LogcatService|com.github.kr328.clash.LogcatService) return 0 ;;
    *) return 1 ;;
  esac
}

short_service() {
  local n="$1"
  n="${n##*.}"
  printf '%s\n' "$n"
}

# --- source: service module -------------------------------------------------

if require_file "$SERVICE_SRC"; then
  if has_perm "$SERVICE_SRC" "FOREGROUND_SERVICE_SPECIAL_USE"; then
    fail "$SERVICE_SRC still declares FOREGROUND_SERVICE_SPECIAL_USE"
  fi
  if ! has_perm "$SERVICE_SRC" "FOREGROUND_SERVICE_SYSTEM_EXEMPTED"; then
    fail "$SERVICE_SRC missing FOREGROUND_SERVICE_SYSTEM_EXEMPTED"
  fi
  if grep -q 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$SERVICE_SRC"; then
    fail "$SERVICE_SRC still declares PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
  fi
  if grep -q 'foregroundServiceType="specialUse"' "$SERVICE_SRC"; then
    fail "$SERVICE_SRC still declares specialUse"
  fi

  tun_ok=0
  typed=0
  while IFS=$'\t' read -r name ftype; do
    [[ -n "$name" ]] || continue
    if [[ -n "$ftype" ]]; then
      typed=$((typed + 1))
      if is_tun_service "$name" && [[ "$ftype" == "systemExempted" ]]; then
        tun_ok=1
      else
        fail "$SERVICE_SRC: $(short_service "$name") has foregroundServiceType=$ftype"
      fi
    fi
  done < <(parse_services "$SERVICE_SRC")
  if ((tun_ok != 1)); then
    fail "$SERVICE_SRC: TunService must be the only typed service and systemExempted"
  fi
  if ((typed != 1)); then
    fail "$SERVICE_SRC: expected exactly one service with foregroundServiceType, found $typed"
  fi
fi

# --- source: app main / debug / release overlay ----------------------------

if require_file "$APP_MAIN_SRC"; then
  if has_perm "$APP_MAIN_SRC" "FOREGROUND_SERVICE_SPECIAL_USE"; then
    fail "$APP_MAIN_SRC still declares FOREGROUND_SERVICE_SPECIAL_USE (must live in $APP_DEBUG_SRC)"
  fi
  if grep -q 'foregroundServiceType="specialUse"' "$APP_MAIN_SRC"; then
    fail "$APP_MAIN_SRC still declares specialUse"
  fi
  if grep -q 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$APP_MAIN_SRC"; then
    fail "$APP_MAIN_SRC still declares PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
  fi
  while IFS=$'\t' read -r name _; do
    [[ -n "$name" ]] || continue
    if is_logcat_service "$name"; then
      fail "$APP_MAIN_SRC still declares LogcatService (must live in $APP_DEBUG_SRC)"
    fi
  done < <(parse_services "$APP_MAIN_SRC")
fi

if require_file "$APP_DEBUG_SRC"; then
  if ! has_perm "$APP_DEBUG_SRC" "FOREGROUND_SERVICE_SPECIAL_USE"; then
    fail "$APP_DEBUG_SRC missing FOREGROUND_SERVICE_SPECIAL_USE for debug LogcatService"
  fi
  if ! grep -q 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$APP_DEBUG_SRC"; then
    fail "$APP_DEBUG_SRC missing PROPERTY_SPECIAL_USE_FGS_SUBTYPE on LogcatService"
  fi
  logcat_ok=0
  while IFS=$'\t' read -r name ftype; do
    [[ -n "$name" ]] || continue
    if is_logcat_service "$name" && [[ "$ftype" == "specialUse" ]]; then
      logcat_ok=1
    elif is_logcat_service "$name"; then
      fail "$APP_DEBUG_SRC: LogcatService foregroundServiceType=${ftype:-<none>}"
    fi
  done < <(parse_services "$APP_DEBUG_SRC")
  if ((logcat_ok != 1)); then
    fail "$APP_DEBUG_SRC must declare LogcatService with specialUse"
  fi
else
  fail "missing $APP_DEBUG_SRC (debug-only LogcatService + SPECIAL_USE)"
fi

if require_file "$APP_RELEASE_SRC"; then
  if grep -q 'LogcatService' "$APP_RELEASE_SRC"; then
    fail "$APP_RELEASE_SRC still mentions LogcatService; it belongs only in $APP_DEBUG_SRC"
  fi
fi

# --- source: worker must not take a foreground lifetime --------------------

if [[ -d "$REFRESH_DIR" ]]; then
  if grep -RInE --include='*.kt' 'setForeground(Async)?[[:space:]]*\(' "$REFRESH_DIR"; then
    fail "$REFRESH_DIR calls setForeground / setForegroundAsync"
  fi
else
  fail "missing $REFRESH_DIR"
fi

# --- source: do not disable WorkManager library FGS ------------------------

disable_hits="$(
  grep -RIn --include='*.xml' \
    'enable_system_foreground_service_default' \
    app/src service/src common/src getlineui/src design/src 2>/dev/null \
    | grep -E 'false' || true
)"
if [[ -n "$disable_hits" ]]; then
  fail "enable_system_foreground_service_default is forced false:"
  printf '%s\n' "$disable_hits" >&2
fi

if ((violations == 0)); then
  echo "OK   source FGS declarations and ProfileRefreshWorker"
fi

# --- merged manifests ------------------------------------------------------

if [[ -z "$MERGED_RELEASE" ]]; then
  MERGED_RELEASE="$DEFAULT_MERGED_RELEASE"
fi

check_merged_release() {
  local merged="$1"
  local name ftype
  local tun_ok=0
  local getline_typed=0

  if grep -qE 'android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"' "$merged"; then
    fail "release merged manifest declares FOREGROUND_SERVICE_SPECIAL_USE ($merged)"
  fi
  if ! grep -qE 'android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"' "$merged"; then
    fail "release merged manifest missing FOREGROUND_SERVICE_SYSTEM_EXEMPTED ($merged)"
  fi
  if grep -q 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$merged"; then
    fail "release merged manifest declares PROPERTY_SPECIAL_USE_FGS_SUBTYPE ($merged)"
  fi
  if grep -q 'foregroundServiceType="specialUse"' "$merged"; then
    fail "release merged manifest declares specialUse ($merged)"
  fi

  while IFS=$'\t' read -r name ftype; do
    [[ -n "$name" ]] || continue
    if is_getline_owned "$name"; then
      if [[ -n "$ftype" ]]; then
        getline_typed=$((getline_typed + 1))
        if is_tun_service "$name" && [[ "$ftype" == "systemExempted" ]]; then
          tun_ok=1
        else
          fail "release merged: GetLine-owned $(short_service "$name") has foregroundServiceType=$ftype ($merged)"
        fi
      fi
    elif [[ -n "$ftype" ]]; then
      # Library service with a type is a new Play surface, not the accepted
      # SystemForegroundService-without-type case.
      fail "release merged: library $(short_service "$name") has foregroundServiceType=$ftype ($merged)"
    fi
  done < <(parse_services "$merged")

  if ((tun_ok != 1)) || ((getline_typed != 1)); then
    fail "release merged: TunService must be the only GetLine-owned typed service and systemExempted ($merged)"
  else
    echo "OK   release merged: TunService systemExempted, no specialUse ($merged)"
  fi
}

check_merged_debug() {
  local merged="$1"
  local name ftype
  local tun_ok=0
  local logcat_ok=0

  if ! grep -qE 'android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"' "$merged"; then
    fail "debug merged manifest missing FOREGROUND_SERVICE_SPECIAL_USE ($merged)"
  fi
  if ! grep -qE 'android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"' "$merged"; then
    fail "debug merged manifest missing FOREGROUND_SERVICE_SYSTEM_EXEMPTED ($merged)"
  fi

  while IFS=$'\t' read -r name ftype; do
    [[ -n "$name" ]] || continue
    if is_tun_service "$name"; then
      if [[ "$ftype" == "systemExempted" ]]; then
        tun_ok=1
      else
        fail "debug merged: TunService foregroundServiceType=${ftype:-<none>} ($merged)"
      fi
    elif is_logcat_service "$name"; then
      if [[ "$ftype" == "specialUse" ]]; then
        logcat_ok=1
      else
        fail "debug merged: LogcatService foregroundServiceType=${ftype:-<none>} ($merged)"
      fi
    fi
  done < <(parse_services "$merged")

  if ((tun_ok != 1)); then
    fail "debug merged: TunService must stay systemExempted ($merged)"
  fi
  if ((logcat_ok != 1)); then
    fail "debug merged: LogcatService must stay specialUse ($merged)"
  fi
  if ((tun_ok == 1)) && ((logcat_ok == 1)); then
    echo "OK   debug merged: TunService systemExempted, LogcatService specialUse ($merged)"
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

if [[ -f "$DEFAULT_MERGED_META_RELEASE" && "$MERGED_RELEASE" != "$DEFAULT_MERGED_META_RELEASE" ]]; then
  check_merged_release "$DEFAULT_MERGED_META_RELEASE"
fi

if [[ -f "$DEFAULT_MERGED_DEBUG" ]]; then
  check_merged_debug "$DEFAULT_MERGED_DEBUG"
elif ((REQUIRE_MERGED)); then
  echo "error: --require-merged set but merged debug manifest not found:" >&2
  echo "       $DEFAULT_MERGED_DEBUG" >&2
  echo "       run: ./gradlew :app:processAlphaProdDebugMainManifest" >&2
  exit 1
fi

if ((violations > 0)); then
  echo "release-fgs-gate: FAIL ($violations violation(s))" >&2
  exit 1
fi

echo "release-fgs-gate: ok"
