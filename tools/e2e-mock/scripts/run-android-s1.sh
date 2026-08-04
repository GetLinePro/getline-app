#!/usr/bin/env bash
# Android S0/S1 UI smoke via adb + uiautomator (emulator or device).
#
# Automates what previously needed manual taps:
#   A) Subscription link (no auth):
#        clear/install → "Enter link manually" → stage YAML URL
#        → dialog must not ClassCastException → Home (e2e-direct)
#   B) Google Auth Tab (existing S1):
#        clear → Sign in with Google → Success on mock page
#        → VPN OK → notification Allow → Home (e2e-direct / Connected)
#        → force-stop → relaunch → Home without browser login
#        → kill :background → UI survives, profile inventory intact, still Home
#        → install -r without pm clear → Home, encrypted store, no re-login
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
#   SKIP_GOOGLE=1 ./tools/e2e-mock/scripts/run-android-s1.sh   # link path only
#   SKIP_SUB_LINK=1 ./tools/e2e-mock/scripts/run-android-s1.sh # Google path only
#
# Env:
#   SERIAL / ANDROID_SERIAL   adb device (default: first `adb devices` entry)
#   PACKAGE                  default pro.getline.vpn.alpha.e2e.debug
#   APK                      optional path; auto-pick x86_64/universal e2e debug
#   SUB_LINK_URL             default https://app.stage.getline.pro/sub/e2e
#   SKIP_INSTALL=1           do not adb install
#   SKIP_CLEAR=1             do not pm clear (dirty run)
#   SKIP_SUB_LINK=1          skip manual subscription-link import path
#   SKIP_GOOGLE=1            skip Google Auth Tab path (+ its persistence)
#   SKIP_PERSISTENCE=1       skip force-stop relaunch (Google path only)
#   SKIP_BACKGROUND_DEATH=1  skip :background kill/recovery (Google path only)
#   SKIP_REINSTALL=1         skip install -r over existing data (Google path only)
#   FORCE_REINSTALL=1        run the install -r check even with SKIP_INSTALL=1
#                            (installs the local APK over whatever is on device)
#   SKIP_IMPORT_HOME=1       skip HOME mid-import survival check on link path
#   SKIP_MARKERS=1           skip local docker log markers
#   DOCKER_CONTAINER         default e2e-mock (local docker only)
#   HOME_TIMEOUT_S           default 90

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PACKAGE="${PACKAGE:-pro.getline.vpn.alpha.e2e.debug}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-e2e-mock}"
HOME_TIMEOUT_S="${HOME_TIMEOUT_S:-90}"
SUB_LINK_URL="${SUB_LINK_URL:-https://app.stage.getline.pro/sub/e2e}"
# Onboarding button that opens the manual URL dialog (get_line_onboarding_enter_link).
LINK_ENTRY_TEXT="${LINK_ENTRY_TEXT:-Enter link manually}"
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

# Tap first EditText (dialog field). Prefer focused=true when present.
ui_tap_edit_text() {
  ui_dump || return 1
  E2E_ADB="${ADB}" E2E_SERIAL="${SERIAL}" python3 <<'PY'
import re, subprocess, sys, os
serial = os.environ["E2E_SERIAL"]
adb_bin = os.environ.get("E2E_ADB") or "adb"
xml = open("/tmp/e2e-ui.xml").read()
nodes = re.findall(r"<node [^>]+/?>", xml)
candidates = []
for n in nodes:
    if "EditText" not in n:
        continue
    bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not bm:
        continue
    x1, y1, x2, y2 = map(int, bm.groups())
    focused = 'focused="true"' in n
    candidates.append((0 if focused else 1, (x1 + x2) // 2, (y1 + y2) // 2))
if not candidates:
    print("no EditText", flush=True)
    sys.exit(1)
candidates.sort()
_, cx, cy = candidates[0]
print(f"tap EditText @ {cx},{cy}", flush=True)
subprocess.check_call(
    [adb_bin, "-s", serial, "shell", "input", "tap", str(cx), str(cy)]
)
sys.exit(0)
PY
}

# Type ASCII into the focused field. Spaces → %s (adb input text rule).
ui_input_text() {
  local raw="$1"
  local encoded
  encoded="${raw// /%s}"
  adb_s shell input text "$encoded"
}

pid_of() {
  adb_s shell "ps -A -o PID,NAME" 2>/dev/null \
    | tr -d '\r' \
    | awk -v n="$1" '$2 == n { print $1; exit }'
}

# Imported profile UUIDs as a sorted line. CMFA stores one directory per
# imported profile, so this is the cheapest honest answer to "did we leak or
# lose a profile" without going through the Binder we are trying to kill.
imported_profile_uuids() {
  adb_s shell run-as "$PACKAGE" ls -1 files/imported 2>/dev/null \
    | tr -d '\r' \
    | sort \
    | tr '\n' ' '
}

# Last cold-start routing breadcrumb (see MainActivity.logStartupRoute).
last_startup_route() {
  adb_s logcat -d 2>/dev/null | grep -o 'startup_route .*' | tail -1
}

# Breadcrumb tokens are space-separated k=v; match on whole tokens only.
assert_route_token() {
  local route="$1" token="$2"
  case " $route " in
    *" $token "*)
      ok "startup_route $token"
      return 0
      ;;
  esac
  fail "startup_route missing $token"
  return 1
}

# True if package process logged a fatal ClassCast (Bug 4 regression).
logcat_has_dialog_classcast() {
  adb_s logcat -d 2>/dev/null \
    | grep -E 'ClassCastException.*(DialogTextField|DialogGetLineTextField|dialog_text_field|dialog_get_line_text_field)' \
    >/dev/null 2>&1
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

clear_app_data() {
  if ! adb_s shell pm clear "$PACKAGE" >/dev/null 2>&1; then
    fail "pm clear $PACKAGE failed"
    return 1
  fi
  ok "pm clear $PACKAGE"
}

# Manual URL import without OAuth — guards the databinding dialog crash (Bug 4)
# and proves stage /sub/e2e YAML reaches Home.
flow_subscription_link() {
  bold "[C-link] Subscription link dialog → YAML import → Home"
  if [[ "${SKIP_SUB_LINK:-0}" == "1" ]]; then
    yellow "SKIP_SUB_LINK=1"
    return 0
  fi

  launch_app

  # Onboarding copy: "I have a subscription link" until #62, "Enter link
  # manually" since QR import landed. Matching only the old text made this whole
  # flow fail on every run from #62 on.
  if ! wait_ui_text "$LINK_ENTRY_TEXT" 30; then
    fail "onboarding: $LINK_ENTRY_TEXT not visible"
    ui_texts || true
    return 1
  fi
  ok "onboarding visible (link path)"

  export E2E_SERIAL="$SERIAL"
  export E2E_ADB="$ADB"
  ui_tap_exact "$LINK_ENTRY_TEXT" || {
    fail "tap $LINK_ENTRY_TEXT"
    return 1
  }
  sleep 1

  if logcat_has_dialog_classcast; then
    fail "ClassCastException after opening subscription-link dialog (Bug 4 regression)"
    return 1
  fi

  if ! wait_ui_text "URL" 10; then
    fail "subscription URL dialog did not open (title URL missing)"
    if logcat_has_dialog_classcast; then
      fail "ClassCastException on dialog inflate"
    fi
    ui_texts || true
    top_activity || true
    return 1
  fi
  ok "subscription URL dialog open (no ClassCast crash)"

  ui_tap_edit_text || {
    fail "could not focus dialog EditText"
    return 1
  }
  sleep 0.3
  ui_input_text "$SUB_LINK_URL"
  sleep 0.5

  if ! ui_texts 2>/dev/null | grep -Fq "$SUB_LINK_URL"; then
    # Some IME paths hide typed text from dump; still try OK if field accepted input.
    yellow "NOTE typed URL not visible in accessibility dump; continuing"
  else
    ok "URL field contains stage subscription link"
  fi

  ui_tap_exact OK || {
    fail "tap OK on subscription URL dialog"
    return 1
  }

  # Mid-import HOME: must not leave a permanent ImportFailed dead-end.
  # Coordinator keeps the fetch; relaunch should reach Home or recoverable Loading.
  if [[ "${SKIP_IMPORT_HOME:-0}" != "1" ]]; then
    sleep 1
    adb_s shell input keyevent KEYCODE_HOME
    ok "pressed HOME during subscription link import"
    sleep 2
    launch_app
    if wait_activity_regex 'GetLineHomeActivity|GetLineOnboardingActivity' "$HOME_TIMEOUT_S"; then
      ok "app resumed after HOME mid-import"
    else
      fail "no product activity after HOME mid-import; last=$(top_activity || true)"
      ui_texts || true
      return 1
    fi
    # Give coordinator time to finish + activate if still in flight.
    if ! wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
      # Still on Onboarding: must not be a permanent dead-end (Retry must work).
      local mid_texts
      mid_texts="$(ui_texts || true)"
      if echo "$mid_texts" | grep -Eqi 'Couldn.t import|Import failed|Не удалось импорт'; then
        # One Retry attempt — durable pending should re-run import.
        ui_tap_exact Retry "Повторить" 2>/dev/null || true
        if ! wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
          fail "ImportFailed dead-end after HOME mid-import; last=$(top_activity || true)"
          ui_texts || true
          return 1
        fi
        ok "Home after Retry post-HOME (import recovered)"
      else
        fail "Home not reached after HOME mid-import; last=$(top_activity || true)"
        ui_texts || true
        return 1
      fi
    else
      ok "GetLineHomeActivity after HOME mid-import (no dead-end)"
    fi
  else
    yellow "SKIP_IMPORT_HOME=1"
    if wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
      ok "GetLineHomeActivity after subscription link import"
    else
      fail "Home not reached via link import within ${HOME_TIMEOUT_S}s; last=$(top_activity || true)"
      ui_texts || true
      if logcat_has_dialog_classcast; then
        fail "ClassCastException during link import"
      fi
      return 1
    fi
  fi

  sleep 2
  local texts
  texts="$(ui_texts || true)"
  if echo "$texts" | grep -Fq "e2e-direct"; then
    ok "UI shows e2e-direct (link path)"
  else
    yellow "NOTE e2e-direct not in UI text after link import"
  fi
  if echo "$texts" | grep -Eq 'Connected|Disconnected|Tap to connect'; then
    ok "Home shell visible (link path)"
  else
    yellow "NOTE Home chrome unexpected after link: $(echo "$texts" | tr '\n' ' ' | head -c 200)"
  fi

  if logcat_has_dialog_classcast; then
    fail "ClassCastException seen during subscription link flow"
    return 1
  fi
  ok "no dialog ClassCastException in logcat"
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

  # Bug 3 guard: after Google login, session prefs and Subscription UI must agree.
  assert_session_subscription_consistent || return 1
}

# After authenticated login: refresh token present; Subscription tab must not show SignedOut.
assert_session_subscription_consistent() {
  bold "[C] Session ↔ Subscription consistency (bug 3 guard)"
  export E2E_SERIAL="$SERIAL"
  export E2E_ADB="$ADB"

  local session_prefs="getline_native_session.xml"
  if ! adb_s shell run-as "$PACKAGE" ls "shared_prefs/$session_prefs" >/dev/null 2>&1; then
    fail "security: encrypted native session prefs missing after Google login"
    return 1
  fi
  ok "encrypted native session prefs present after login"
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session_fallback.xml >/dev/null 2>&1; then
    fail "security: legacy plaintext fallback session prefs exist after login"
    return 1
  fi

  # Soft check: refresh_token key present (encrypted values not readable as plain).
  local prefs_dump
  prefs_dump=$(adb_s shell run-as "$PACKAGE" cat "shared_prefs/$session_prefs" 2>/dev/null || true)
  if echo "$prefs_dump" | grep -q 'refresh_token'; then
    ok "session prefs mention refresh_token key"
  else
    # EncryptedSharedPreferences may obfuscate key names — file presence is the hard assert.
    yellow "NOTE refresh_token key not plain in prefs dump (encrypted?); file presence still OK"
  fi

  if ! ui_tap_exact "Subscription"; then
    fail "bug3: could not open Subscription tab after login"
    return 1
  fi
  sleep 2
  local texts
  texts="$(ui_texts || true)"
  if echo "$texts" | grep -Fq "Subscription management unavailable"; then
    fail "bug3: SignedOut card after Google login (session/binding desync)"
    return 1
  fi
  if echo "$texts" | grep -Fq "Управление подпиской недоступно"; then
    fail "bug3: SignedOut card (ru) after Google login"
    return 1
  fi
  if echo "$texts" | grep -Fq "E2E Plan"; then
    ok "Subscription tab shows E2E Plan (session-backed)"
  else
    # Empty/Failed still means not SignedOut — acceptable soft pass with note.
    if echo "$texts" | grep -Eqi 'Sign in to GetLine|Войдите в GetLine'; then
      fail "bug3: sign-in CTA on Subscription after authenticated login"
      return 1
    fi
    yellow "NOTE E2E Plan not visible yet; SignedOut not shown (texts: $(echo "$texts" | tr '\n' ' ' | head -c 180))"
    ok "Subscription tab not SignedOut after login"
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
  # Every run is a keystore regression gate. Plaintext fallback is never supported.
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session.xml >/dev/null 2>&1; then
    ok "encrypted native session prefs present"
  else
    fail "persistence: encrypted session prefs missing"
  fi
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session_fallback.xml >/dev/null 2>&1; then
    fail "persistence security: legacy plaintext fallback session prefs exist"
  fi
}

# The VPN and the whole profile store live in :background. Killing it must not
# take the UI process with it, must not lose a profile, and must not drop the
# user back on onboarding — the cold-start policy treats a dead backend as
# "unknown", not as "no subscription".
#
# `am kill` is a no-op here: the process is not in a killable state while the UI
# holds a binding. The kill goes through run-as under the app's own uid, which
# works on debuggable builds only — the same builds this script installs.
flow_background_death() {
  bold "[E] :background process death"
  if [[ "${SKIP_BACKGROUND_DEATH:-0}" == "1" ]]; then
    yellow "SKIP_BACKGROUND_DEATH=1"
    return 0
  fi
  export E2E_SERIAL="$SERIAL"
  export E2E_ADB="$ADB"

  local ui_before bg_before profiles_before
  ui_before="$(pid_of "$PACKAGE")"
  bg_before="$(pid_of "$PACKAGE:background")"
  profiles_before="$(imported_profile_uuids)"

  if [[ -z "$bg_before" ]]; then
    fail ":background is not running — nothing to kill"
    return 1
  fi
  if [[ -z "${profiles_before// /}" ]]; then
    fail "no imported profile before the kill — the scenario would prove nothing"
    return 1
  fi
  ok ":background pid=$bg_before, imported: $profiles_before"

  if ! adb_s shell run-as "$PACKAGE" kill -9 "$bg_before" >/dev/null 2>&1; then
    fail "run-as kill -9 $bg_before failed (non-debuggable build?)"
    return 1
  fi
  sleep 3

  local ui_after bg_after
  ui_after="$(pid_of "$PACKAGE")"
  bg_after="$(pid_of "$PACKAGE:background")"
  if [[ "$bg_after" == "$bg_before" ]]; then
    fail "kill did not land — :background pid unchanged ($bg_after)"
    return 1
  fi
  ok ":background died (pid $bg_before → ${bg_after:-none})"
  if [[ -n "$ui_before" && "$ui_after" == "$ui_before" ]]; then
    ok "UI process survived (pid $ui_after)"
  else
    fail "UI process died with :background ($ui_before → ${ui_after:-none})"
  fi

  launch_app
  if ! wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
    fail "expected Home after :background death; got $(top_activity || true)"
    ui_texts || true
    return 1
  fi
  ok "Home after :background death"

  local texts
  texts="$(ui_texts || true)"
  if echo "$texts" | grep -Fq "Sign in with Google"; then
    fail ":background death dropped the user back on onboarding"
    return 1
  fi
  ok "no onboarding after :background death"

  # One recovery tap, only if the screen is offering one.
  if echo "$texts" | grep -Eq 'Retry|Повторить'; then
    ui_tap_exact Retry "Повторить" >/dev/null 2>&1 || true
    sleep 2
    if wait_activity_regex 'GetLineHomeActivity' 30; then
      ok "Home after one Retry"
    else
      fail "Retry after :background death left $(top_activity || true)"
      return 1
    fi
  fi

  local profiles_after
  profiles_after="$(imported_profile_uuids)"
  if [[ "$profiles_after" == "$profiles_before" ]]; then
    ok "imported profiles unchanged"
  else
    fail "profile inventory changed: '$profiles_before' → '$profiles_after'"
    return 1
  fi
}

# `install -r` keeps the data directory. The failure this guards is silent loss
# of the encrypted session or reintroduction of a second backing store. The
# startup_route breadcrumb carries the evidence needed to distinguish that from
# a genuinely empty session.
#
# The same APK is reinstalled on purpose: the failure is about surviving a
# replace, not about a migration between two versions — there are none.
flow_reinstall() {
  bold "[F] install -r over existing data (no pm clear)"
  if [[ "${SKIP_REINSTALL:-0}" == "1" ]]; then
    yellow "SKIP_REINSTALL=1"
    return 0
  fi
  # SKIP_INSTALL=1 means "smoke whatever is already on the device". Installing a
  # local artifact here would replace it with a possibly different build: the
  # replacement is no longer same-APK, a lower versionCode fails outright, and
  # either way the device is modified by a run that promised not to.
  if [[ "${SKIP_INSTALL:-0}" == "1" && "${FORCE_REINSTALL:-0}" != "1" ]]; then
    yellow "SKIP reinstall — SKIP_INSTALL=1 (FORCE_REINSTALL=1 to install the local APK anyway)"
    return 0
  fi
  local apk
  apk="$(pick_apk)"
  if [[ -z "$apk" ]]; then
    yellow "NOTE no APK available to reinstall — skip"
    return 0
  fi

  local profiles_before
  profiles_before="$(imported_profile_uuids)"
  if [[ -z "${profiles_before// /}" ]]; then
    fail "no imported profile before reinstall — the scenario would prove nothing"
    return 1
  fi

  adb_s shell am force-stop "$PACKAGE"
  local out
  if ! out=$(adb_s install -r "$apk" 2>&1); then
    fail "install -r failed for $(basename "$apk")"
    printf '%s\n' "$out" | sed 's/^/  /' || true
    return 1
  fi
  if printf '%s\n' "$out" | grep -qiE 'Failure|Error:'; then
    fail "install -r reported failure for $(basename "$apk")"
    printf '%s\n' "$out" | sed 's/^/  /' || true
    return 1
  fi
  ok "install -r $(basename "$apk") (data kept)"

  adb_s logcat -c 2>/dev/null || true
  launch_app
  if ! wait_activity_regex 'GetLineHomeActivity' "$HOME_TIMEOUT_S"; then
    fail "expected Home after reinstall; got $(top_activity || true)"
    ui_texts || true
    return 1
  fi
  ok "Home after reinstall (no sign-in required)"

  local route
  route="$(last_startup_route)"
  if [[ -z "$route" ]]; then
    fail "no startup_route breadcrumb after reinstall"
    return 1
  fi
  echo "  $route"
  assert_route_token "$route" "dest=home" || return 1
  assert_route_token "$route" "session=1" || return 1
  assert_route_token "$route" "managed=1" || return 1
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session.xml >/dev/null 2>&1; then
    ok "encrypted native session prefs survived reinstall"
  else
    fail "security: encrypted native session prefs missing after reinstall"
    return 1
  fi
  if adb_s shell run-as "$PACKAGE" ls shared_prefs/getline_native_session_fallback.xml >/dev/null 2>&1; then
    fail "security: legacy plaintext fallback session prefs survived reinstall"
    return 1
  fi

  local profiles_after
  profiles_after="$(imported_profile_uuids)"
  if [[ "$profiles_after" == "$profiles_before" ]]; then
    ok "imported profiles survived reinstall"
  else
    fail "profiles lost across reinstall: '$profiles_before' → '$profiles_after'"
    return 1
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
  echo "SERIAL=$SERIAL PACKAGE=$PACKAGE ADB=$ADB SUB_LINK_URL=$SUB_LINK_URL"
  chrome_note

  if ! install_fresh; then
    red "Android S1 smoke: FAIL (install/clear)"
    exit 1
  fi

  SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "SINCE=$SINCE"
  adb_s logcat -c 2>/dev/null || true

  # A) Manual subscription link — no Auth Tab; proves dialog inflate + YAML import.
  flow_subscription_link || true

  # B) Google Auth Tab S1 — needs a clean process/data so login is not skipped.
  if [[ "${SKIP_GOOGLE:-0}" == "1" ]]; then
    yellow "SKIP_GOOGLE=1"
  else
    if [[ "${SKIP_SUB_LINK:-0}" != "1" ]]; then
      # Link path left a managed profile; clear so onboarding/Google is reachable.
      clear_app_data || true
      adb_s logcat -c 2>/dev/null || true
      SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "SINCE(google)=$SINCE"
    fi
    # Gate persistence on the login flow itself, not on the global FAIL: any
    # earlier failure — including one in the unrelated link path — used to skip
    # the relaunch check silently, and it stayed skipped for several PRs.
    # Markers are advisory (they no-op when docker is not on this machine) and
    # must not gate it either.
    login_ok=0
    if flow_login; then
      login_ok=1
    fi
    check_server_markers || true
    # E and F assert session=1 / managed=1, so they need the authenticated
    # state the login flow leaves behind — same gate as persistence.
    if [[ "$login_ok" -eq 1 ]]; then
      flow_persistence || true
      flow_background_death || true
      flow_reinstall || true
    else
      yellow "SKIP persistence / :background death / reinstall — login flow failed"
    fi
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
