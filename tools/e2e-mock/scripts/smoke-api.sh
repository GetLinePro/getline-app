#!/usr/bin/env bash
# API contract smoke for e2e-mock (curl only).
#
# This is NOT Android S0/S1 smoke. It does not prove:
#   Auth Tab open, DAL verification, callback into APK,
#   Android session persistence, or profile import on device.
#
# Use watch-android-smoke.sh + manual device steps for Android proof.
#
# Usage:
#   BASE_API=https://app.stage.getline.pro BASE_AUTH=https://auth.stage.getline.pro ./scripts/smoke-api.sh
#   BASE_API=http://127.0.0.1:8080 BASE_AUTH=http://127.0.0.1:8080 ./scripts/smoke-api.sh
#
# All requests send X-E2E-Client: api-smoke so server logs show source=api_smoke.

set -euo pipefail

BASE_API="${BASE_API:-http://127.0.0.1:8080}"
BASE_AUTH="${BASE_AUTH:-http://127.0.0.1:8080}"

# Synthetic fixed tokens (not production secrets). Values are known mock fixtures;
# script never prints full token values in stage labels.
S0_TOKEN="s0-auth-token"
S1_DEVICE_KEY="s1-device-key"
S1_ACCESS="s1-native-access-token"
S1_REFRESH="s1-native-refresh-token"
S1_ACCESS_REFRESHED="s1-native-access-token-refreshed"
S1_REFRESH_REFRESHED="s1-native-refresh-token-refreshed"

CLIENT_HDR=( -H 'X-E2E-Client: api-smoke' )
TMPDIR_SMOKE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_SMOKE"' EXIT

FAIL=0
PASS_N=0
FAIL_N=0

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# Expected negative: want_code is the intended reject status (401/400).
# Unexpected: infrastructure (000, 5xx) or wrong status → fail.
expect_http() {
  local tag="$1" code="$2" want="$3"
  if [[ "$code" == "000" ]]; then
    red "FAIL $tag → connection failed (infrastructure)"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
    return 1
  fi
  if [[ "$code" =~ ^5 ]]; then
    red "FAIL $tag → HTTP $code (infrastructure / server error)"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
    return 1
  fi
  if [[ "$code" == "$want" ]]; then
    green "OK   $tag → HTTP $code (expected)"
    PASS_N=$((PASS_N + 1))
    return 0
  fi
  red "FAIL $tag → HTTP $code (want $want)"
  FAIL=1
  FAIL_N=$((FAIL_N + 1))
  return 1
}

json_field() {
  local body="$1" field="$2"
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1],""))' "$field" <<<"$body" 2>/dev/null || true
}

expect_json_field() {
  local tag="$1" body="$2" field="$3" expected="$4"
  local got
  got="$(json_field "$body" "$field")"
  if [[ "$got" == "$expected" ]]; then
    green "OK   $tag → $field matches"
    PASS_N=$((PASS_N + 1))
    return 0
  fi
  red "FAIL $tag → $field mismatch (got non-empty=${#got} chars; expected fixture)"
  FAIL=1
  FAIL_N=$((FAIL_N + 1))
  return 1
}

# curl wrapper: writes body to file, prints http code on stdout.
# Does not use -f so expected 4xx are not shell failures.
http_get() {
  local out="$1"; shift
  curl -sS -o "$out" -w '%{http_code}' "${CLIENT_HDR[@]}" "$@" || echo "000"
}

http_post() {
  local out="$1"; shift
  curl -sS -o "$out" -w '%{http_code}' "${CLIENT_HDR[@]}" "$@" || echo "000"
}

bold "API contract smoke (not Android S0/S1)"
echo "BASE_API=$BASE_API"
echo "BASE_AUTH=$BASE_AUTH"
echo "Client tag: X-E2E-Client: api-smoke → server source=api_smoke"
echo

# --- Preflight (infrastructure) ---
bold "[PREFLIGHT] health + static mock surface"

out="$TMPDIR_SMOKE/health.json"
code="$(http_get "$out" "$BASE_API/__health")"
expect_http "[PREFLIGHT] GET /__health" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[PREFLIGHT] health status" "$body" status ok || true
  expect_json_field "[PREFLIGHT] health slice" "$body" slice S1 || true
fi

out="$TMPDIR_SMOKE/start.json"
code="$(http_get "$out" "$BASE_API/api/auth/google/start")"
expect_http "[PREFLIGHT] GET /api/auth/google/start" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[PREFLIGHT] auth_url" "$body" auth_url \
    "https://auth.stage.getline.pro/__mock__/google" || true
fi

out="$TMPDIR_SMOKE/google.html"
code="$(http_get "$out" "$BASE_AUTH/__mock__/google")"
expect_http "[PREFLIGHT] GET /__mock__/google" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  if grep -q 'Success' "$out" && grep -q 's0-auth-token' "$out"; then
    green "OK   [PREFLIGHT] mock google HTML has Success + token marker"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [PREFLIGHT] mock google HTML missing Success or token marker"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
fi

# Auth Tab Success navigates to completion host root (fragment not sent to server).
# Contract: 200 + Cache-Control: no-store (same as previous smoke.sh).
bold "[PREFLIGHT] Auth Tab completion page"
out="$TMPDIR_SMOKE/completion.html"
hdr="$TMPDIR_SMOKE/completion.hdr"
code="$(curl -sS -D "$hdr" -o "$out" -w '%{http_code}' "${CLIENT_HDR[@]}" "$BASE_AUTH/" || echo "000")"
expect_http "[PREFLIGHT] GET / (completion)" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  if grep -qi 'Cache-Control:.*no-store' "$hdr"; then
    green "OK   [PREFLIGHT] completion Cache-Control: no-store"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [PREFLIGHT] completion missing Cache-Control: no-store"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
fi

# --- /api/auth/me ---
bold "[NEGATIVE] /me rejects wrong web token"
out="$TMPDIR_SMOKE/me-bad.json"
code="$(http_get "$out" \
  -H 'Authorization: Bearer wrong-token' \
  -H 'Accept: application/json' \
  "$BASE_API/api/auth/me")"
expect_http "[NEGATIVE] /me wrong web token" "$code" "401" || true

bold "[POSITIVE] /me accepts correct web token"
out="$TMPDIR_SMOKE/me-ok.json"
code="$(http_get "$out" \
  -H "Authorization: Bearer ${S0_TOKEN}" \
  -H 'Accept: application/json' \
  "$BASE_API/api/auth/me")"
expect_http "[POSITIVE] /me correct web token" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[POSITIVE] /me customer_id" "$body" customer_id e2e-user || true
  expect_json_field "[POSITIVE] /me username" "$body" username e2e@getline.invalid || true
fi

# --- device-key generate ---
bold "[NEGATIVE] device-key generate rejects wrong web token"
out="$TMPDIR_SMOKE/dk-bad.json"
code="$(http_get "$out" \
  -H 'Authorization: Bearer wrong-token' \
  -H 'Accept: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  "$BASE_API/api/auth/device-key/generate")"
expect_http "[NEGATIVE] generate wrong web token" "$code" "401" || true

bold "[POSITIVE] device-key generate issues key"
out="$TMPDIR_SMOKE/dk-ok.json"
code="$(http_get "$out" \
  -H "Authorization: Bearer ${S0_TOKEN}" \
  -H 'Accept: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  "$BASE_API/api/auth/device-key/generate")"
expect_http "[POSITIVE] generate issues key" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[POSITIVE] generate device_key" "$body" device_key "$S1_DEVICE_KEY" || true
fi

# --- device-key exchange ---
bold "[NEGATIVE] exchange rejects wrong device key"
out="$TMPDIR_SMOKE/ex-bad.json"
code="$(http_post "$out" \
  -X POST \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -H "Origin: ${BASE_API}" \
  -H "Referer: ${BASE_API}/" \
  -d '{"device_key":"wrong-key"}' \
  "$BASE_API/api/auth/device-key/exchange")"
expect_http "[NEGATIVE] exchange wrong device key" "$code" "400" || true

bold "[POSITIVE] exchange accepts issued device key"
out="$TMPDIR_SMOKE/ex-ok.json"
code="$(http_post "$out" \
  -X POST \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -H "Origin: ${BASE_API}" \
  -H "Referer: ${BASE_API}/" \
  -d "{\"device_key\":\"${S1_DEVICE_KEY}\"}" \
  "$BASE_API/api/auth/device-key/exchange")"
# No Authorization header — matches real Android client.
expect_http "[POSITIVE] exchange issued key (no Bearer)" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[POSITIVE] exchange access_token" "$body" access_token "$S1_ACCESS" || true
  expect_json_field "[POSITIVE] exchange refresh_token" "$body" refresh_token "$S1_REFRESH" || true
  got_exp="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("expires_in",""))' <<<"$body" 2>/dev/null || true)"
  if [[ "$got_exp" == "3600" ]]; then
    green "OK   [POSITIVE] exchange expires_in=3600"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [POSITIVE] exchange expires_in (want 3600)"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
fi

# --- subscriptions ---
bold "[NEGATIVE] subscriptions rejects wrong native token"
out="$TMPDIR_SMOKE/sub-bad.json"
code="$(http_get "$out" \
  -H 'Authorization: Bearer wrong-native' \
  -H 'Accept: application/json' \
  "$BASE_API/api/subscriptions")"
expect_http "[NEGATIVE] subscriptions wrong native token" "$code" "401" || true

bold "[POSITIVE] subscriptions returns active subscription"
out="$TMPDIR_SMOKE/sub-ok.json"
code="$(http_get "$out" \
  -H "Authorization: Bearer ${S1_ACCESS}" \
  -H 'Accept: application/json' \
  "$BASE_API/api/subscriptions")"
expect_http "[POSITIVE] subscriptions correct native token" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  link="$(python3 -c '
import json,sys
d=json.load(sys.stdin)
subs=d.get("subscriptions") or []
print(subs[0].get("subscription_link","") if subs else "")
' <<<"$body" 2>/dev/null || true)"
  if [[ "$link" == "https://app.stage.getline.pro/sub/e2e" ]]; then
    green "OK   [POSITIVE] subscription_link fixture"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [POSITIVE] subscription_link shape/fixture"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
  active="$(python3 -c '
import json,sys
d=json.load(sys.stdin)
subs=d.get("subscriptions") or []
print(subs[0].get("is_active") if subs else None)
' <<<"$body" 2>/dev/null || true)"
  if [[ "$active" == "True" ]]; then
    green "OK   [POSITIVE] is_active=true"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [POSITIVE] is_active expected true"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
fi

# --- subscription YAML ---
bold "[POSITIVE] subscription YAML is reachable"
out="$TMPDIR_SMOKE/profile.yaml"
code="$(http_get "$out" "$BASE_API/sub/e2e")"
expect_http "[POSITIVE] GET /sub/e2e" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  if grep -q 'proxy-groups:' "$out" && grep -q 'rules:' "$out"; then
    green "OK   [POSITIVE] YAML has proxy-groups + rules"
    PASS_N=$((PASS_N + 1))
  else
    red "FAIL [POSITIVE] YAML missing required Clash sections"
    FAIL=1
    FAIL_N=$((FAIL_N + 1))
  fi
fi

# --- native refresh (API stub only; app first-login does not call this) ---
bold "[POSITIVE] refresh accepts valid refresh token"
out="$TMPDIR_SMOKE/refresh-ok.json"
code="$(http_post "$out" \
  -X POST \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -H "Origin: ${BASE_API}" \
  -H "Referer: ${BASE_API}/" \
  -d "{\"refresh_token\":\"${S1_REFRESH}\"}" \
  "$BASE_API/api/auth/native/refresh")"
expect_http "[POSITIVE] native refresh" "$code" "200" || true
if [[ "$code" == "200" ]]; then
  body="$(cat "$out")"
  expect_json_field "[POSITIVE] refresh access_token" "$body" access_token "$S1_ACCESS_REFRESHED" || true
  expect_json_field "[POSITIVE] refresh refresh_token" "$body" refresh_token "$S1_REFRESH_REFRESHED" || true
fi

echo
if [[ "$FAIL" -ne 0 ]]; then
  red "API smoke: FAIL  (passed=$PASS_N failed_checks=$FAIL_N)"
  echo "Note: expected 401/400 on [NEGATIVE] steps are successes; failures above are unexpected."
  exit 1
fi
green "API smoke: PASS  (checks=$PASS_N)"
echo
echo "This script proves HTTP contract endpoints only."
echo "It does NOT prove Auth Tab, DAL, APK callback, Android session store, or profile import."
echo "For Android markers, use: ./scripts/watch-android-smoke.sh"
exit 0
