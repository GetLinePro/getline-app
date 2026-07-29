#!/usr/bin/env bash
# Foundation acceptance (point 6): automated checks that already passed once.
#
# Proves:
#   A build (debug variants + metaProd package via BuildConfig)
#   B package IDs / debug SHA-256 / public assetlinks
#   F host isolation unit tests
#   G public stage HTTPS health (curl only — no SSH / no host shell)
#   API contract smoke against stage (separate from Android UI)
#
# Does NOT prove full Android Auth Tab UI by itself — use:
#   ./scripts/run-android-s1.sh
#
# Does NOT SSH into deploy hosts. Docker/Caddy host checks stay manual on the
# machine that runs the mock (see README deploy section).
#
# Usage (from repo root or this directory):
#   ./tools/e2e-mock/scripts/accept-foundation.sh
#   SKIP_BUILD=1 SKIP_UNIT=1 ./tools/e2e-mock/scripts/accept-foundation.sh
#
# Env:
#   BASE_API        default https://app.stage.getline.pro
#   BASE_AUTH       default https://auth.stage.getline.pro
#   SKIP_BUILD=1    skip gradle assemble
#   SKIP_UNIT=1     skip isolation unit tests
#   SKIP_API=1      skip smoke-api.sh
#   SKIP_ASSETLINKS=1
#   SKIP_STAGE=1    skip public __health curls
#   EXPECT_DEBUG_SHA256  override expected debug cert fingerprint

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

BASE_API="${BASE_API:-https://app.stage.getline.pro}"
BASE_AUTH="${BASE_AUTH:-https://auth.stage.getline.pro}"
EXPECT_DEBUG_SHA256="${EXPECT_DEBUG_SHA256:-BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB}"
E2E_PKG="pro.getline.vpn.alpha.e2e.debug"
META_PKG="pro.getline.vpn"
ALPHA_PROD_PKG="pro.getline.vpn.alpha.debug"

FAIL=0
PASS_N=0
FAIL_N=0

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

ok() {
  green "OK   $*"
  PASS_N=$((PASS_N + 1))
}

fail() {
  red "FAIL $*"
  FAIL=1
  FAIL_N=$((FAIL_N + 1))
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    fail "missing command: $1"
    return 1
  }
}

# --- A. Build ----------------------------------------------------------------

section_build() {
  bold "[A] Build variants"
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    yellow "SKIP_BUILD=1"
    return 0
  fi
  need_cmd ./gradlew || return 0

  if ./gradlew :app:assembleAlphaProdDebug :app:assembleAlphaE2eDebug --console=plain; then
    ok "assembleAlphaProdDebug + assembleAlphaE2eDebug"
  else
    fail "assembleAlphaProdDebug / assembleAlphaE2eDebug"
  fi

  # Package/hosts proof without requiring a signed AAB.
  if ./gradlew :app:generateMetaProdReleaseBuildConfig :app:processMetaProdReleaseMainManifest --console=plain; then
    ok "metaProdRelease BuildConfig + main manifest generated (unsigned package proof)"
  else
    fail "metaProdRelease BuildConfig/manifest generation"
  fi

  # Signed AAB only when BOTH signing.properties and release.keystore exist.
  # Gradle creates signingConfig "release" only if signing.properties exists;
  # without it (or without the keystore file) the bundle may be unsigned.
  # Presence of keystore alone is not enough — verify the output AAB is signed.
  local has_props=0 has_store=0
  [[ -f signing.properties ]] && has_props=1
  [[ -f release.keystore ]] && has_store=1
  if [[ "$has_props" -eq 1 && "$has_store" -eq 1 ]]; then
    if ./gradlew :app:bundleMetaProdRelease --console=plain; then
      local aab
      aab=$(find app/build/outputs/bundle/metaProdRelease -name '*.aab' 2>/dev/null | head -1 || true)
      if [[ -z "$aab" || ! -f "$aab" ]]; then
        fail "bundleMetaProdRelease produced no .aab under app/build/outputs/bundle/metaProdRelease"
      elif ! command -v jarsigner >/dev/null 2>&1; then
        fail "jarsigner missing — cannot verify AAB signature"
      else
        # Match .github/workflows/build-release.yaml:
        #   jarsigner -verify -verbose -certs
        # Require "jar verified"; reject unsigned and Android Debug.
        # Do NOT use -strict: Android release certs are self-signed and
        # -strict fails with "signer errors" / exit 4 on valid production AABs.
        jarsigner -verify -verbose -certs "$aab" >/tmp/e2e-aab-jarsigner.txt 2>&1 || true
        if grep -qi 'jar is unsigned' /tmp/e2e-aab-jarsigner.txt; then
          fail "bundleMetaProdRelease AAB is unsigned"
          sed 's/^/  /' /tmp/e2e-aab-jarsigner.txt | head -20 || true
        elif ! grep -q 'jar verified' /tmp/e2e-aab-jarsigner.txt; then
          fail "bundleMetaProdRelease AAB signature verification failed (no 'jar verified')"
          sed 's/^/  /' /tmp/e2e-aab-jarsigner.txt | head -20 || true
        elif grep -qiE 'Android Debug|CN=Android Debug|AndroidDebugKey' /tmp/e2e-aab-jarsigner.txt; then
          fail "bundleMetaProdRelease AAB appears debug-signed"
          sed 's/^/  /' /tmp/e2e-aab-jarsigner.txt | head -20 || true
        else
          ok "bundleMetaProdRelease AAB signed (jarsigner verified, non-debug)"
        fi
      fi
    else
      fail "bundleMetaProdRelease (signing material present but build failed)"
    fi
  else
    yellow "NOTE signed metaProdRelease bundle skipped (need signing.properties + release.keystore; props=$has_props store=$has_store)"
  fi
}

# --- B. Package / signing / assetlinks ---------------------------------------

find_buildconfig() {
  local flavor="$1"
  local bt="$2"
  # e.g. app/build/generated/source/buildConfig/alphaE2e/debug/com/github/kr328/clash/BuildConfig.java
  find "app/build/generated/source/buildConfig/${flavor}/${bt}" -name BuildConfig.java 2>/dev/null | head -1
}

check_buildconfig_field() {
  local file="$1" field="$2" want="$3" label="$4"
  if [[ ! -f "$file" ]]; then
    fail "$label: BuildConfig missing ($file)"
    return
  fi
  if rg -q "String $field = \"$want\"" "$file" || rg -q "$field = \"$want\"" "$file"; then
    ok "$label: $field=$want"
  else
    fail "$label: expected $field=$want in $file"
    rg -n "$field" "$file" || true
  fi
}

section_package() {
  bold "[B] Package IDs + debug SHA-256 + assetlinks"

  local e2e_bc prod_bc meta_bc
  e2e_bc=$(find_buildconfig alphaE2e debug)
  prod_bc=$(find_buildconfig alphaProd debug)
  meta_bc=$(find_buildconfig metaProd release)

  check_buildconfig_field "$e2e_bc" APPLICATION_ID "$E2E_PKG" "alphaE2eDebug"
  check_buildconfig_field "$e2e_bc" GETLINE_API_ORIGIN "https://app.stage.getline.pro" "alphaE2eDebug"
  check_buildconfig_field "$e2e_bc" GETLINE_AUTH_ORIGIN "https://auth.stage.getline.pro" "alphaE2eDebug"
  check_buildconfig_field "$e2e_bc" GETLINE_CALLBACK_HOST "auth.stage.getline.pro" "alphaE2eDebug"
  check_buildconfig_field "$e2e_bc" GETLINE_PORTAL_ORIGIN "https://app.stage.getline.pro" "alphaE2eDebug"

  check_buildconfig_field "$prod_bc" APPLICATION_ID "$ALPHA_PROD_PKG" "alphaProdDebug"
  check_buildconfig_field "$prod_bc" GETLINE_API_ORIGIN "https://app.getline.pro" "alphaProdDebug"
  check_buildconfig_field "$prod_bc" GETLINE_CALLBACK_HOST "app.getline.pro" "alphaProdDebug"

  check_buildconfig_field "$meta_bc" APPLICATION_ID "$META_PKG" "metaProdRelease"
  check_buildconfig_field "$meta_bc" GETLINE_API_ORIGIN "https://app.getline.pro" "metaProdRelease"
  check_buildconfig_field "$meta_bc" GETLINE_CALLBACK_HOST "app.getline.pro" "metaProdRelease"

  # APK package if present
  local aapt=""
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk_root" ]]; then
    aapt=$(ls "$sdk_root"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)
  fi
  local e2e_apk=""
  # Prefer x86_64 for emulator; fall back to any alphaE2e debug APK.
  e2e_apk=$(find app/build/outputs/apk/alphaE2e/debug -name '*x86_64*.apk' 2>/dev/null | head -1 || true)
  if [[ -z "$e2e_apk" ]]; then
    e2e_apk=$(find app/build/outputs/apk/alphaE2e/debug -name '*.apk' 2>/dev/null | head -1 || true)
  fi
  if [[ -n "$aapt" && -n "$e2e_apk" && -f "$e2e_apk" ]]; then
    local pkg
    # badging line: package: name='…' versionCode=…  (do not match platformBuildVersionName)
    pkg=$("$aapt" dump badging "$e2e_apk" 2>/dev/null | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -1)
    if [[ "$pkg" == "$E2E_PKG" ]]; then
      ok "alphaE2eDebug APK package=$pkg"
    else
      fail "alphaE2eDebug APK package=${pkg:-<empty>} want $E2E_PKG"
    fi
  else
    yellow "NOTE aapt/APK skip (aapt=${aapt:-missing} apk=${e2e_apk:-missing})"
  fi

  # signingReport SHA-256 for alphaE2eDebug — empty/unparsed SHA is FAIL (DAL proof).
  local signing_rc=0
  ./gradlew :app:signingReport --console=plain >/tmp/e2e-signingReport.txt 2>&1 || signing_rc=$?
  if [[ "$signing_rc" -ne 0 ]]; then
    fail "signingReport failed (exit $signing_rc) — cannot prove debug cert matches DAL"
  else
    local sha
    sha=$(awk '
      /Variant: alphaE2eDebug$/ {grab=1; next}
      /Variant:/ {grab=0}
      grab && /SHA-256:/ {print $2; exit}
    ' /tmp/e2e-signingReport.txt)
    if [[ -z "$sha" ]]; then
      fail "could not parse alphaE2eDebug SHA-256 from signingReport (empty)"
    elif [[ "$sha" == "$EXPECT_DEBUG_SHA256" ]]; then
      ok "alphaE2eDebug SHA-256 matches DAL expectation"
    else
      fail "alphaE2eDebug SHA-256=$sha want $EXPECT_DEBUG_SHA256"
    fi
  fi

  if [[ "${SKIP_ASSETLINKS:-0}" == "1" ]]; then
    yellow "SKIP_ASSETLINKS=1"
    return 0
  fi
  need_cmd curl || return 0
  local hdr body code ctype
  hdr=$(mktemp)
  body=$(mktemp)
  code=$(curl -sS -D "$hdr" -o "$body" -w '%{http_code}' \
    "${BASE_AUTH}/.well-known/assetlinks.json" || echo 000)
  ctype=$(tr -d '\r' <"$hdr" | awk -F': ' 'tolower($1)=="content-type"{print $2; exit}')
  if [[ "$code" == "200" ]]; then
    ok "assetlinks HTTP 200"
  else
    fail "assetlinks HTTP $code"
  fi
  if echo "$ctype" | grep -qi 'application/json'; then
    ok "assetlinks content-type application/json"
  else
    fail "assetlinks content-type=$ctype"
  fi
  if rg -q "\"package_name\": \"$E2E_PKG\"" "$body" && rg -q "$EXPECT_DEBUG_SHA256" "$body"; then
    ok "assetlinks lists $E2E_PKG + expected SHA-256"
  else
    fail "assetlinks missing $E2E_PKG or SHA-256"
  fi
  # no redirect: curl without -L should still be 200 on final URL
  local url_eff
  url_eff=$(curl -sS -o /dev/null -w '%{url_effective}' "${BASE_AUTH}/.well-known/assetlinks.json")
  if [[ "$url_eff" == "${BASE_AUTH}/.well-known/assetlinks.json" ]]; then
    ok "assetlinks no redirect"
  else
    fail "assetlinks redirected to $url_eff"
  fi
  rm -f "$hdr" "$body"
}

# --- F. Unit tests -----------------------------------------------------------

section_unit() {
  bold "[F] Host isolation unit tests"
  if [[ "${SKIP_UNIT:-0}" == "1" ]]; then
    yellow "SKIP_UNIT=1"
    return 0
  fi
  local tests=(
    'pro.getline.vpn.GetLineControlPlaneHostPolicyTest'
    'pro.getline.vpn.getline.auth.ControlPlaneIsolationIntegrationTest'
    'pro.getline.vpn.getline.auth.AuthCallbackParserTest'
    'pro.getline.vpn.getline.auth.BrowserAuthLauncherValidationTest'
    'pro.getline.vpn.getline.auth.BrowserAuthStarterTest'
    'pro.getline.vpn.getline.auth.SubscriptionLoadRepositoryTest'
  )
  local args=()
  local t
  for t in "${tests[@]}"; do
    args+=(--tests "$t")
  done
  if ./gradlew :app:testAlphaE2eDebugUnitTest "${args[@]}" \
    :app:testAlphaProdDebugUnitTest "${args[@]}" --console=plain; then
    ok "isolation unit tests (alphaE2eDebug + alphaProdDebug)"
  else
    fail "isolation unit tests"
  fi
}

# --- API smoke + stage health ------------------------------------------------

section_api() {
  bold "[API] smoke-api.sh against stage (not Android UI)"
  if [[ "${SKIP_API:-0}" == "1" ]]; then
    yellow "SKIP_API=1"
    return 0
  fi
  if BASE_API="$BASE_API" BASE_AUTH="$BASE_AUTH" \
    bash "$ROOT/tools/e2e-mock/scripts/smoke-api.sh"; then
    ok "API smoke PASS ($BASE_API)"
  else
    fail "API smoke"
  fi
}

section_stage() {
  bold "[G] Public stage health (HTTPS only — no SSH)"
  if [[ "${SKIP_STAGE:-0}" == "1" ]]; then
    yellow "SKIP_STAGE=1"
    return 0
  fi
  need_cmd curl || return 0
  local code body
  body=$(mktemp)
  code=$(curl -sS -o "$body" -w '%{http_code}' "$BASE_API/__health" || echo 000)
  if [[ "$code" == "200" ]] && rg -q '"status"[[:space:]]*:[[:space:]]*"ok"' "$body"; then
    ok "public $BASE_API/__health"
  else
    fail "public __health HTTP $code body=$(head -c 120 "$body")"
  fi
  code=$(curl -sS -o "$body" -w '%{http_code}' "$BASE_AUTH/__health" || echo 000)
  if [[ "$code" == "200" ]]; then
    ok "public $BASE_AUTH/__health"
  else
    fail "public auth __health HTTP $code"
  fi
  rm -f "$body"
}

# --- summary -----------------------------------------------------------------

main() {
  bold "E2E foundation acceptance (automated)"
  echo "REPO=$ROOT"
  echo "BASE_API=$BASE_API BASE_AUTH=$BASE_AUTH"

  section_build
  section_package
  section_unit
  section_api
  section_stage

  bold "Summary"
  if [[ "$FAIL" -eq 0 ]]; then
    green "Foundation automated acceptance: PASS  (ok=$PASS_N)"
    echo "Next: Android S0/S1 UI + persistence → ./tools/e2e-mock/scripts/run-android-s1.sh"
    exit 0
  fi
  red "Foundation automated acceptance: FAIL  (ok=$PASS_N fail=$FAIL_N)"
  exit 1
}

main "$@"
