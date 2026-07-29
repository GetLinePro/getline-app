#!/usr/bin/env bash
# Android S0/S1 smoke helper (manual Auth Tab + server/logcat markers).
#
# Does NOT automate the Auth Tab Success tap.
# Does NOT run API contract smoke (use smoke-api.sh for that).
# API smoke requests are tagged source=api_smoke and are ignored here.
#
# Usage:
#   ./scripts/watch-android-smoke.sh              # start: clear logcat, print steps, wait
#   ./scripts/watch-android-smoke.sh --check-only # only inspect logs since --since / default window
#
# Env:
#   PACKAGE          default pro.getline.vpn.alpha.e2e.debug
#   DOCKER_CONTAINER default e2e-mock
#   SINCE            docker logs --since value (default: start of this run)
#   ADB              adb binary (default: adb)
#   SKIP_ADB=1       skip logcat clear / adb steps
#   SKIP_DOCKER=1    skip docker log fetch

set -euo pipefail

PACKAGE="${PACKAGE:-pro.getline.vpn.alpha.e2e.debug}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-e2e-mock}"
ADB="${ADB:-adb}"
CHECK_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --check-only) CHECK_ONLY=1 ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
  esac
done

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

START_EPOCH="$(date +%s)"
START_ISO="$(date -u -d "@$START_EPOCH" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$START_EPOCH" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ)"
SINCE="${SINCE:-$START_ISO}"

# Required happy-path checks (app traffic only; source=api_smoke stripped).
# Reject-only lines (e.g. device_key_issued=false) must NOT satisfy PASS.
# Each entry: "label|needle1|needle2|..." — all needles must appear on one line.

fetch_docker_logs() {
  if [[ "${SKIP_DOCKER:-0}" == "1" ]]; then
    yellow "SKIP_DOCKER=1 — not reading container logs"
    return 1
  fi
  if ! command -v docker >/dev/null 2>&1; then
    yellow "docker not found — cannot read $DOCKER_CONTAINER logs"
    return 1
  fi
  if ! docker inspect "$DOCKER_CONTAINER" >/dev/null 2>&1; then
    yellow "container $DOCKER_CONTAINER not found (set DOCKER_CONTAINER or run against stage host)"
    return 1
  fi
  docker logs --since "$SINCE" "$DOCKER_CONTAINER" 2>&1 || true
  return 0
}

# True if some line of $1 contains every remaining fixed-string argument.
# No grep pipelines (pipefail + grep -q early-exit SIGPIPE false negatives).
log_line_has_all() {
  local haystack="$1"
  shift
  local line ok n
  while IFS= read -r line || [[ -n "$line" ]]; do
    ok=1
    for n in "$@"; do
      case "$line" in
        *"$n"*) ;;
        *) ok=0; break ;;
      esac
    done
    if [[ "$ok" -eq 1 ]]; then
      return 0
    fi
  done <<<"$haystack"
  return 1
}

check_markers() {
  local logs="$1"
  local app_logs
  # Drop API smoke; here-string avoids pipefail/SIGPIPE with grep -q.
  app_logs="$(grep -v 'source=api_smoke' <<<"$logs" || true)"

  bold "Android flow markers since $SINCE (excluding source=api_smoke)"
  # label|required substrings on the same line
  local -a required=(
    "google start|GET /api/auth/google/start"
    "me matching web token|me_requested|web_token_matches=true"
    "device key issued|device_key_issued|device_key_issued=true"
    "exchange succeeded|device_key_exchange_succeeded|device_key_matches=true"
    "subscriptions matching native token|subscriptions_requested|native_token_matches=true"
    "subscription YAML requested|subscription_yaml_requested"
  )

  local missing=0
  local entry label rest
  local -a needles
  for entry in "${required[@]}"; do
    label="${entry%%|*}"
    rest="${entry#*|}"
    IFS='|' read -r -a needles <<<"$rest"
    if log_line_has_all "$app_logs" "${needles[@]}"; then
      if log_line_has_all "$app_logs" "${needles[@]}" "source=app"; then
        green "OK   $label  (source=app)"
      else
        green "OK   $label  (present; not api_smoke)"
      fi
    else
      red "MISS $label  (need same-line: ${needles[*]})"
      missing=$((missing + 1))
    fi
  done

  echo
  local total="${#required[@]}"
  if [[ "$missing" -eq 0 ]]; then
    green "Android server markers: PASS ($total / $total)"
    echo "Still verify on device: Home opened; force-stop + relaunch restores profile (no browser)."
    return 0
  fi
  red "Android server markers: FAIL (missing=$missing / $total)"
  return 1
}

print_steps() {
  bold "Android S0/S1 manual steps"
  cat <<EOF
Package: $PACKAGE
Watch start: $START_ISO (SINCE=$SINCE)

1. Install alphaE2eDebug if needed:
     ./gradlew :app:assembleAlphaE2eDebug
2. Clear app data (recommended for clean S0):
     ${ADB} shell pm clear ${PACKAGE}
3. Launch app (or open from launcher):
     ${ADB} shell monkey -p ${PACKAGE} -c android.intent.category.LAUNCHER 1
4. Start Google login → mock Google page → tap Success.
5. Confirm Home opens with imported profile.
6. Force-stop, relaunch, confirm profile restored without browser login.

Expected server sequence (source=app, not source=api_smoke):
  google start
  me with matching web token
  device key issued
  exchange succeeded
  subscriptions with matching native token
  subscription YAML requested

API contract curl smoke is separate:
  ./scripts/smoke-api.sh
Do not treat source=api_smoke log lines as Android flow.
EOF
}

if [[ "$CHECK_ONLY" -eq 0 ]]; then
  print_steps

  if [[ "${SKIP_ADB:-0}" != "1" ]] && command -v "$ADB" >/dev/null 2>&1; then
    bold "Clearing logcat"
    "$ADB" logcat -c 2>/dev/null && green "OK   logcat cleared" || yellow "WARN could not clear logcat"
  else
    yellow "adb skipped or missing (SKIP_ADB=${SKIP_ADB:-0})"
  fi

  bold "Waiting for manual Success"
  echo "Complete the Auth Tab flow on the device, then press Enter to check server markers."
  echo "Or re-run later: SINCE=$SINCE $0 --check-only"
  # Allow non-interactive use: if no TTY, sleep hint only
  if [[ -t 0 ]]; then
    read -r -p "Press Enter after Success… " _
  else
    yellow "No TTY — not waiting. Re-run with --check-only after the device flow."
    exit 0
  fi
fi

bold "Fetching mock logs"
LOGS=""
if LOGS="$(fetch_docker_logs)"; then
  :
else
  yellow "No docker logs available. Paste mock logs from the host or set DOCKER_CONTAINER."
  if [[ -t 0 && "$CHECK_ONLY" -eq 0 ]]; then
    echo "Paste logs ending with a line containing only END, then Enter:"
    LOGS="$(sed -n '/^END$/q;p')"
  else
    exit 2
  fi
fi

if [[ -z "${LOGS//[[:space:]]/}" ]]; then
  yellow "Empty log window since $SINCE"
  exit 2
fi

check_markers "$LOGS"
exit $?
