#!/usr/bin/env bash
# Android S0/S1 UI smoke via adb + uiautomator (emulator or device).
#
# Automates what previously needed manual taps:
#   clear/install → Sign in with Google → Success on mock page
#   → VPN OK → notification Allow → Home (e2e-direct / Connected)
#   → force-stop → relaunch → Home without browser login
#   → optional local docker markers (only if e2e-mock is on this machine)
#
# No SSH to deploy hosts. Remote mock logs: check on the host yourself or use
# watch-android-smoke.sh with local docker / pasted logs.
#
# Not a full Espresso suite. Fragile to UI copy/layout changes.
# Chrome WebView must expose the Success control to accessibility.
#
# Usage:
#   ./tools/e2e-mock/scripts/run-android-s1.sh
#   SERIAL=emulator-5554 SKIP_INSTALL=1 ./tools/e2e-mock/scripts/run-android-s1.sh
#
# Env:
#   SERIAL / ANDROID_SERIAL   adb device (default: first `adb devices` entry)
#   PACKAGE                  default pro.getline.vpn.alpha.e2e.debug
#   APK                      optional path; auto-pick x86_64/universal e2e debug
#   SKIP_INSTALL=1           do not adb install
#   SKIP_CLEAR=1             do not pm clear (dirty run)
#   SKIP_PERSISTENCE=1       skip force-stop relaunch
#   SKIP_MARKERS=1           skip local docker log markers
#   DOCKER_CONTAINER         default e2e-mock (local docker only)
#   HOME_TIMEOUT_S           default 90

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PACKAGE="${PACKAGE:-pro.getline.vpn.alpha.e2e.debug}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-e2e-mock}"
HOME_TIMEOUT_S="${HOME_TIMEOUT_S:-90}"
ADB="${ADB:-adb}"

FAIL=0
PASS_N=0

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

ok() { green "OK   $*"; PASS_N=$((PASS_N + 1)); }
fail() { red "FAIL $*"; FAIL=1; }

resolve_serial() {
  if [[ -n "${SERIAL:-${ANDROID_SERIAL:-}}" ]]; then
    SERIAL="${SERIAL:-$ANDROID_SERIAL}"
    return 0
  fi
  SERIAL="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
  if [[ -z "$SERIAL" ]]; then
    fail "no adb device; start emulator or set SERIAL="
    exit 2
  fi
}

adb_s() {
  "$ADB" -s "$SERIAL" "$@"
}

# --- UI helpers (python + uiautomator dump) -----------------------------------

ui_dump() {
  adb_s shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 1
  adb_s pull /sdcard/window_dump.xml /tmp/e2e-ui.xml >/dev/null 2>&1 || return 1
}

top_activity() {
  adb_s shell dumpsys activity activities 2>/dev/null \
    | awk '/topResumedActivity=/{print; exit}'
}

ui_texts() {
  ui_dump || return 1
  python3 - <<'PY'
import re
xml=open('/tmp/e2e-ui.xml').read()
for t in re.findall(r'text="([^"]+)"', xml):
    print(t)
PY
}

# Tap first node with exact text or content-desc in needles.
# Prefer clickable=true. order: exact text, exact desc.
ui_tap_exact() {
  ui_dump || return 1
  # Use documented $ADB (not PATH adb) — export E2E_ADB before calling.
  E2E_ADB="${ADB}" E2E_SERIAL="${SERIAL}" python3 - "$@" <<'PY'
import re, subprocess, sys, os
needles = sys.argv[1:]
serial = os.environ["E2E_SERIAL"]
adb_bin = os.environ.get("E2E_ADB") or "adb"
xml = open("/tmp/e2e-ui.xml").read()
nodes = re.findall(r"<node [^>]+/?>", xml)

def try_tap(require_clickable: bool) -> bool:
    for n in nodes:
        if require_clickable and 'clickable="true"' not in n:
            continue
        tm = re.search(r'text="([^"]*)"', n)
        cm = re.search(r'content-desc="([^"]*)"', n)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not bm:
            continue
        labels = [x for x in ((tm.group(1) if tm else ""), (cm.group(1) if cm else "")) if x]
        for lab in labels:
            if lab in needles:
                x1, y1, x2, y2 = map(int, bm.groups())
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                print(f"tap {lab!r} @ {cx},{cy}", flush=True)
                subprocess.check_call(
                    [adb_bin, "-s", serial, "shell", "input", "tap", str(cx), str(cy)]
                )
                return True
    return False

if try_tap(True) or try_tap(False):
    sys.exit(0)
print("no exact match for", needles, flush=True)
sys.exit(1)
PY
}

wait_activity_regex() {
  local re="$1" timeout_s="${2:-60}"
  local i
  for ((i = 1; i <= timeout_s; i++)); do
    local act
    act="$(top_activity || true)"
    if [[ "$act" =~ $re ]]; then
      echo "$act"
      return 0
    fi
    # dismiss common system dialogs while waiting
    case "$act" in
      *ConfirmDialog*) ui_tap_exact OK Allow 2>/dev/null || true ;;
      *GrantPermissionsActivity*) ui_tap_exact Allow OK 2>/dev/null || true ;;
    esac
    sleep 1
  done
  return 1
}

wait_ui_text() {
  local needle="$1" timeout_s="${2:-30}"
  local i
  for ((i = 1; i <= timeout_s; i++)); do
    if ui_texts 2>/dev/null | grep -Fqx "$needle" || ui_texts 2>/dev/null | grep -Fq "$needle"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# --- steps -------------------------------------------------------------------

pick_apk() {
  if [[ -n "${APK:-}" && -f "$APK" ]]; then
    echo "$APK"
    return 0
  fi
  local f
  f=$(ls "$ROOT"/app/build/outputs/apk/alphaE2e/debug/*x86_64*.apk 2>/dev/null | head -1 || true)
  if [[ -z "$f" ]]; then
    f=$(ls "$ROOT"/app/build/outputs/apk/alphaE2e/debug/*universal*.apk 2>/dev/null | head -1 || true)
  fi
  if [[ -z "$f" ]]; then
    f=$(ls "$ROOT"/app/build/outputs/apk/alphaE2e/debug/*arm64*.apk 2>/dev/null | head -1 || true)
  fi
  echo "${f:-}"
}

install_fresh() {
  bold "[C] Install / clear $PACKAGE on $SERIAL"
  # When called from `if ! install_fresh`, bash disables set -e inside this
  # function. Every failure path must return 1 explicitly (never ok-on-fail).
  if [[ "${SKIP_INSTALL:-0}" != "1" ]]; then
    local apk
    apk="$(pick_apk)"
    if [[ -z "$apk" ]]; then
      fail "no alphaE2eDebug APK; build with ./gradlew :app:assembleAlphaE2eDebug or set APK="
      return 1
    fi
    local install_out
    if ! install_out=$(adb_s install -r "$apk" 2>&1); then
      fail "adb install failed for $(basename "$apk") — refuse to smoke an older APK"
      printf '%s\n' "$install_out" | sed 's/^/  /' || true
      return 1
    fi
    # adb can exit 0 with Failure in output on some versions
    if printf '%s\n' "$install_out" | grep -qiE 'Failure|Error:'; then
      fail "adb install reported failure for $(basename "$apk")"
      printf '%s\n' "$install_out" | sed 's/^/  /' || true
      return 1
    fi
    ok "installed $(basename "$apk")"
  else
    yellow "SKIP_INSTALL=1 (will use whatever is already on device)"
  fi
  if [[ "${SKIP_CLEAR:-0}" != "1" ]]; then
    if ! adb_s shell pm clear "$PACKAGE" >/dev/null 2>&1; then
      fail "pm clear $PACKAGE failed"
      return 1
    fi
    ok "pm clear $PACKAGE"
  else
    yellow "SKIP_CLEAR=1"
  fi
  return 0
}

launch_app() {
  adb_s shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2
}

flow_login() {
  bold "[C] Google mock login → Success → Home"
  launch_app

  if ! wait_ui_text "Sign in with Google" 30; then
    fail "onboarding: Sign in with Google not visible"
    ui_texts || true
    return 1
  fi
  ok "onboarding visible"

  export E2E_SERIAL="$SERIAL"
  ui_tap_exact "Sign in with Google" || {
    fail "tap Sign in with Google"
    return 1
  }

  # Auth Tab / Custom Tab WebView — wait for Success control
  if ! wait_ui_text "Success" 45; then
    fail "mock page Success not in accessibility tree (Chrome/Auth Tab?)"
    top_activity || true
    ui_texts || true
    return 1
  fi
  ok "mock Success visible"
  ui_tap_exact "Success" || {
    fail "tap Success"
    return 1
  }

  # Session path then optional VPN + notification dialogs → Home
  if wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
    ok "GetLineHomeActivity"
  else
    fail "Home not reached within ${HOME_TIMEOUT_S}s; last=$(top_activity || true)"
    ui_texts || true
    return 1
  fi

  # Profile / connected evidence (best-effort)
  sleep 2
  local texts
  texts="$(ui_texts || true)"
  if echo "$texts" | grep -Fq "e2e-direct"; then
    ok "UI shows e2e-direct"
  else
    yellow "NOTE e2e-direct not in UI text (may still be active profile)"
  fi
  if echo "$texts" | grep -Eq 'Connected|Disconnected|Tap to connect'; then
    ok "Home shell visible"
  else
    yellow "NOTE Home chrome texts unexpected: $(echo "$texts" | tr '\n' ' ' | head -c 200)"
  fi
}

check_server_markers() {
  bold "[C] Local docker markers since $SINCE (source!=api_smoke)"
  if [[ "${SKIP_MARKERS:-0}" == "1" ]]; then
    yellow "SKIP_MARKERS=1"
    return 0
  fi
  # Local docker only — never SSH to a deploy host from this script.
  if ! command -v docker >/dev/null 2>&1; then
    yellow "NOTE docker not local — skip markers (UI proof still counts)"
    return 0
  fi
  if ! docker inspect "$DOCKER_CONTAINER" >/dev/null 2>&1; then
    yellow "NOTE container $DOCKER_CONTAINER not on this machine — skip markers"
    return 0
  fi
  local logs
  logs=$(docker logs --since "$SINCE" "$DOCKER_CONTAINER" 2>&1 || true)
  if [[ -z "${logs//[[:space:]]/}" ]]; then
    yellow "NOTE empty local docker logs since $SINCE"
    return 0
  fi
  local app_logs
  app_logs=$(grep -v 'source=api_smoke' <<<"$logs" || true)

  check_line() {
    local label="$1"
    shift
    local line ok_n n
    while IFS= read -r line || [[ -n "$line" ]]; do
      ok_n=1
      for n in "$@"; do
        case "$line" in
          *"$n"*) ;;
          *) ok_n=0; break ;;
        esac
      done
      if [[ "$ok_n" -eq 1 ]]; then
        ok "$label"
        return 0
      fi
    done <<<"$app_logs"
    fail "marker missing: $label ($*)"
    return 1
  }

  check_line "me matching web token" me_requested web_token_matches=true || true
  check_line "device key issued" device_key_issued device_key_issued=true || true
  check_line "exchange succeeded" device_key_exchange_succeeded device_key_matches=true || true
  check_line "subscriptions matching native token" subscriptions_requested native_token_matches=true || true
  check_line "subscription YAML requested" subscription_yaml_requested || true
}

flow_persistence() {
  bold "[D] Persistence after force-stop"
  if [[ "${SKIP_PERSISTENCE:-0}" == "1" ]]; then
    yellow "SKIP_PERSISTENCE=1"
    return 0
  fi
  adb_s shell am force-stop "$PACKAGE"
  sleep 1
  if adb_s shell pidof "$PACKAGE" >/dev/null 2>&1; then
    fail "process still running after force-stop"
  else
    ok "force-stop"
  fi
  launch_app
  if wait_activity_regex 'GetLineHomeActivity' 30; then
    ok "Home after relaunch (no browser login required)"
  else
    fail "expected Home after relaunch; got $(top_activity || true)"
    ui_texts || true
    return 1
  fi
  local texts
  texts="$(ui_texts || true)"
  if echo "$texts" | grep -Fq "Sign in with Google"; then
    fail "onboarding after relaunch — session not restored"
  else
    ok "no Google onboarding after relaunch"
  fi
  # Required persistence evidence — soft NOTE is not enough for PASS.
  export E2E_SERIAL="$SERIAL"
  export E2E_ADB="$ADB"
  if ! ui_tap_exact "Subscription"; then
    fail "could not open Subscription tab for persistence check"
  else
    sleep 1
    texts="$(ui_texts || true)"
    if echo "$texts" | grep -Fq "E2E Plan"; then
      ok "Subscription tab shows E2E Plan"
    else
      fail "persistence: E2E Plan missing after relaunch (texts: $(echo "$texts" | tr '\n' ' ' | head -c 200))"
    fi
  fi
  # Default e2e debug package is debuggable — run-as must see session store.
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session.xml >/dev/null 2>&1; then
    ok "encrypted native session prefs present"
  else
    fail "persistence: shared_prefs/getline_native_session.xml missing (session not saved or run-as failed)"
  fi
}

chrome_note() {
  bold "Device / Chrome"
  adb_s shell getprop ro.product.model || true
  adb_s shell getprop ro.build.version.release || true
  adb_s shell dumpsys package com.android.chrome 2>/dev/null \
    | awk '/versionName=/{print; if(++n>=2) exit}' || true
}

main() {
  bold "Android S1 smoke (adb UI)"
  resolve_serial
  export E2E_SERIAL="$SERIAL"
  export E2E_ADB="$ADB"
  echo "SERIAL=$SERIAL PACKAGE=$PACKAGE ADB=$ADB"
  chrome_note

  if ! install_fresh; then
    red "Android S1 smoke: FAIL (install/clear)"
    exit 1
  fi

  SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "SINCE=$SINCE"
  adb_s logcat -c 2>/dev/null || true

  # Collect FAIL flags from each phase; do not abort mid-run after login starts,
  # but never claim PASS if any phase failed.
  flow_login || true
  check_server_markers || true
  if [[ "$FAIL" -eq 0 ]]; then
    flow_persistence || true
  else
    yellow "SKIP persistence — login/markers already failed"
  fi

  bold "Summary"
  if [[ "$FAIL" -eq 0 ]]; then
    green "Android S1 smoke: PASS  (ok=$PASS_N)"
    exit 0
  fi
  red "Android S1 smoke: FAIL  (ok=$PASS_N)"
  exit 1
}

main "$@"
