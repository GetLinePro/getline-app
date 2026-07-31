# e2e-mock — synthetic stage backend for GetLine Android

## Назначение

Mock is a **synthetic external world** around a real `alphaE2eDebug` APK.

It proves the black-box path:

```text
Auth Tab → web token → device-key → native session → subscriptions → YAML import → Home
```

It does **not**:

- replace repository unit fakes or in-process test doubles;
- emulate full production RWP Shop / payments / plan catalog / device CRUD;
- call `bot.getline.pro` or `backend-app`.

Detailed HTTP contract (headers, bodies, tolerances):  
[`docs/spikes/e2e-auth-session-contract.md`](../../docs/spikes/e2e-auth-session-contract.md)

---

## Окружения

| | Production | E2E |
| --- | --- | --- |
| API | `https://app.getline.pro` | `https://app.stage.getline.pro` |
| Auth / Auth Tab host | `https://app.getline.pro` | `https://auth.stage.getline.pro` |
| Callback host | `app.getline.pro` | `auth.stage.getline.pro` |
| Working package | `pro.getline.vpn` | `pro.getline.vpn.alpha.e2e.debug` |
| Build variant | `metaProd*` / `alphaProd*` | **`alphaE2eDebug` only** |

e2e is enabled only for `alpha` + `debug` (`app/build.gradle.kts`).  
Production package and release builds stay on `prod`.

---

## Статус срезов

| Slice | Status |
| --- | --- |
| S0 Auth Tab handoff | **green** |
| S1 Native session + subscription import | **green** |
| S2 Email OTP | **not started** |
| Custom Tabs fallback (no Auth Tab browser) | **deferred** |

Also verified for this foundation:

- flavors `channel` × `environment` (working: `alphaE2eDebug`);
- prod/e2e host isolation;
- persistence after force-stop (emulator);
- physical device smoke;
- stage mock only — no production RWP Shop.

---

## Инфраструктурная схема

```text
Android (alphaE2eDebug)
  → public HTTPS (app.stage / auth.stage)
  → HTTPS reverse proxy at the edge
  → e2e-mock Docker service :8080 (internal only)
```

Deployment layout (host, compose file, Docker network, container names)
is part of the private deployment configuration and is not documented here.
The mock listens on `:8080` inside its container and publishes no host port.

### Hosts served by mock (via Caddy)

| Host | Role |
| --- | --- |
| `app.stage.getline.pro` | API + subscription YAML (`/sub/e2e`) |
| `auth.stage.getline.pro` | Mock Google page + HTTPS completion (`/`) + DAL |

Do **not** put secrets or real production access tokens in this tree or in logs.

---

## Изоляция E2E от production

Isolation has two layers: **Android control plane** and **server stage edge**.
Neither replaces the other.

### Android (product layer)

`alphaE2eDebug` is built with environment flavor **e2e** only:

| Field | Value |
| --- | --- |
| package | `pro.getline.vpn.alpha.e2e.debug` |
| API origin | `https://app.stage.getline.pro` |
| auth origin | `https://auth.stage.getline.pro` |
| callback host | `auth.stage.getline.pro` |
| portal origin | `https://app.stage.getline.pro` |

Source: `app/build.gradle.kts` (`productFlavors` `prod` / `e2e`), runtime
`AppEnvironment` + `GetLineControlPlaneHostPolicy`.

**Control-plane allowlist (e2e):**

- `app.stage.getline.pro`
- `auth.stage.getline.pro`

Applied **before** network / browser launch to:

- API origin (`RwpGetLineAuthApi`)
- browser `auth_url` launch
- Auth Tab callback host
- account portal host
- `subscription_link` from `GET /api/subscriptions` (import path)

**Redirect hardening:**

- Control-plane API (`RwpGetLineAuthApi`): `instanceFollowRedirects = false`; 3xx → protocol error (no follow to production).
- Subscription YAML fetch (`Clash.fetchAndValid` / `openUrl`): same-host redirects only (`SameHostOnlyRedirect`) so an allowlisted stage URL cannot `Location` to `app.getline.pro`; HTTPS→HTTP downgrades are also rejected.
- Hostnames are canonicalized (lowercase, strip trailing FQDN dots) so `auth.stage.getline.pro.` cannot bypass GetLine-family checks on prod.

Rejected on e2e (controlled `Protocol` / callback error, no request):

- `app.getline.pro`, `bot.getline.pro`, `auth.getline.pro`
- arbitrary external hosts (including real Google OAuth)

**Not covered by this allowlist:** Mihomo VPN traffic and proxy endpoints inside
an imported profile. Isolation is GetLine auth/session/subscription control
plane only.

Unit evidence (run both flavors):

```bash
./gradlew :app:testAlphaE2eDebugUnitTest \
  :app:testAlphaProdDebugUnitTest \
  --tests 'pro.getline.vpn.GetLineControlPlaneHostPolicyTest' \
  --tests 'pro.getline.vpn.getline.auth.ControlPlaneIsolationIntegrationTest'
```

Package / BuildConfig evidence (no APK string-grep as sole proof):

```bash
./gradlew :app:generateAlphaE2eDebugBuildConfig \
  :app:generateMetaProdReleaseBuildConfig
# APPLICATION_ID / GETLINE_* in:
#   app/build/generated/source/buildConfig/alphaE2e/debug/.../BuildConfig.java
#   app/build/generated/source/buildConfig/metaProd/release/.../BuildConfig.java
```

Expected:

| Variant | APPLICATION_ID |
| --- | --- |
| `alphaE2eDebug` | `pro.getline.vpn.alpha.e2e.debug` |
| `metaProdRelease` | `pro.getline.vpn` |

### Server (stage edge)

| Check | Expected |
| --- | --- |
| Published host port for mock | **none** (`expose: ["8080"]` only) |
| Reachability | only through the edge reverse proxy; no host port |
| Caddy stage blocks | `reverse_proxy e2e-mock:8080` only (`private deployment configuration`) |
| Fallback to production RWP | **none** — no `bot.getline.pro` / `backend-app` upstream |
| Mock outbound | mock does **not** call production RWP Shop (`main.go` header + no client) |
| Compose credentials | no production secrets in `private deployment configuration` |
| Synthetic tokens | `s0-auth-token`, `s1-*` are **mock fixtures**, not production secrets |

Local `docker run -p 8080:8080` in this README is for **developer loop only**.
Host deploy must not publish mock ports.

Runtime proof of client isolation remains: S0/S1 happy path on stage + no
production requests in mock/device logs for `alphaE2eDebug`.

---

## Совместимость браузера

Documented **observations** only (not a Chromium guarantee):

| Environment | Observation |
| --- | --- |
| Chrome **150** on **emulator-5554** | Auth Tab category available; full S0/S1 flow works |
| Older test Chrome on emulator | Auth Tab path **did not** work |
| Observed failure band | Problem reproduced on **Chrome 137 and older** |

This is **not** an official “Android only on Chrome 138+” product claim.  
It is the current observed boundary from our devices.

Also:

- **Custom Tabs fallback is not implemented** (deferred).
- That is **compatibility work**, not a rejection of the session/API architecture.
- The primary E2E emulator must keep a **fixed working Chrome version** (currently **150** on emulator-5554).

Without an Auth Tab-capable browser the client throws `No Auth Tab-capable browser`  
(`BrowserAuthLauncher`) — there is no silent fallback yet.

---

## Как повторить S0 (Auth Tab handoff)

Build / install:

```bash
./gradlew :app:assembleAlphaE2eDebug
# APK under app/build/outputs/apk/… — package: pro.getline.vpn.alpha.e2e.debug
```

Manual:

1. Install `alphaE2eDebug`.
2. Clear app data (fresh install or Settings → Clear data).
3. Open app → Google login.
4. On mock Google page, tap **Success**.
5. Auth Tab should close; mock logs should show `device-key/generate` (and related API hits).

Expect callback fragment (client-side only; fragment not sent to server):

```text
https://auth.stage.getline.pro/#/login?auth_token=s0-auth-token&expires_in=300
```

S0 alone does not require subscriptions/YAML; it proves Auth Tab + web token + generate.

---

## Как повторить S1 (native session + import)

1. Run full **S0** flow above.
2. Mock logs: `device_key_exchange_succeeded` with `device_key_matches=true`  
   (typical: `bearer_present=false` — app does **not** send Bearer on exchange).
3. Mock logs: `subscriptions_requested` with `native_token_matches=true`.
4. Mock logs: `subscription_yaml_requested` (`GET /sub/e2e`).
5. App opens **Home** with imported profile.
6. Force-stop the app.
7. Launch again.
8. Home restores the **correct profile** with **no browser login**.

Acceptance checklist:

1. Google mock Success closes Auth Tab.
2. No permanent `Couldn’t sign in` / `AuthFailed` after a successful path.
3. Mock logs successful device-key exchange.
4. App stores native session; requests subscriptions with native access.
5. Profile `/sub/e2e` imports and activates; Home opens.
6. Kill + relaunch → Home from saved native session.
7. Production RWP Shop / production API not involved.

---

## Tokens (synthetic)

| Role | Value | Used by |
| --- | --- | --- |
| Web `auth_token` | `s0-auth-token` | Bearer for `/api/auth/me`, `/api/auth/device-key/generate` |
| Device key | `s1-device-key` | JSON body field `device_key` on exchange only |
| Native access | `s1-native-access-token` | Bearer for `/api/subscriptions` |
| Native refresh | `s1-native-refresh-token` | Body of refresh stub (smoke / recovery) |
| After refresh stub | `s1-native-access-token-refreshed` / `…-refreshed` | mock only |

These are fixed stage fakes, not production secrets.

### Exchange note

`RwpGetLineAuthApi.exchangeDeviceKey` sends **no** `Authorization` header (same as live RWP).  
Mock accepts that: only matching `device_key` is required. If a Bearer is present and wrong → `401`.

### API smoke vs Android smoke (different proofs)

| | **API contract smoke** | **Android S0/S1 smoke** | **Foundation acceptance** |
| --- | --- | --- | --- |
| Script | `scripts/smoke-api.sh` (`smoke.sh` → same) | `scripts/run-android-s1.sh` (adb UI) or `watch-android-smoke.sh` (markers + manual) | `scripts/accept-foundation.sh` |
| Proves | HTTP status + JSON shape on mock endpoints | Auth Tab/CCT, DAL, APK callback, session, import, persistence; **manual subscription-link dialog → YAML → Home** (no OAuth) | Build variants, package/BuildConfig hosts, debug SHA+DAL, isolation unit tests, public stage health/API smoke |
| Does **not** prove | Auth Tab / DAL / APK session / import | Full curl negative matrix; production signed bundle | Full device UI alone (calls out to `run-android-s1.sh`) |
| Log tag | `X-E2E-Client: api-smoke` → `source=api_smoke` | no test header → `source=app` | n/a (orchestrates other checks) |

**Never call API curl smoke “S0 Android smoke”.**  
S0 is Auth Tab + DAL handoff on a real APK. The API script may hit the same endpoints S0/S1 use, but that only proves the **HTTP contract**.

Scripts do **not** SSH into deploy hosts. Docker/Caddy host checks are manual on the machine that runs the mock.

### Foundation acceptance (point 6)

```bash
# From repo root — automated A/B/F/G + public stage API smoke
./tools/e2e-mock/scripts/accept-foundation.sh

# Faster re-check after a green build:
SKIP_BUILD=1 SKIP_UNIT=1 ./tools/e2e-mock/scripts/accept-foundation.sh

# Android UI S1 + persistence on emulator/device (adb required):
SERIAL=emulator-5554 ./tools/e2e-mock/scripts/run-android-s1.sh

# Link path only (no Auth Tab; guards dialog ClassCast + YAML import):
SKIP_GOOGLE=1 SERIAL=emulator-5554 ./tools/e2e-mock/scripts/run-android-s1.sh

# Google path only:
SKIP_SUB_LINK=1 SERIAL=emulator-5554 ./tools/e2e-mock/scripts/run-android-s1.sh
```

`run-android-s1.sh` uses `uiautomator` dumps, in order:

1. **Subscription link** — “I have a subscription link” → dialog URL  
   `SUB_LINK_URL` (default `https://app.stage.getline.pro/sub/e2e`) → Home  
   (`e2e-direct`). Asserts no `ClassCastException` on the product text dialog.
2. **Google Auth Tab** — clear → Google → Success → VPN OK → notification Allow  
   → Home → force-stop relaunch / Subscription “E2E Plan”.

It is best-effort against accessibility text; pin a working Chrome (currently **150** on emulator-5554) for the Google path.

`false → true` transitions in server logs during `smoke-api.sh` are **script negatives** (`[NEGATIVE]` then `[POSITIVE]`), not Android retries or stale sessions. Do not treat them as app bugs without new evidence.

First-login app path does **not** call `POST /api/auth/native/refresh` (`establishFromWebToken`).  
Refresh lines during API smoke are the curl script, not the APK.

---

## Routes

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/__health` | `{"status":"ok","slice":"S1",...}` |
| `GET` | `/api/auth/google/start` | `{"auth_url":"https://auth.stage.getline.pro/__mock__/google"}` |
| `GET` | `/__mock__/google` | HTML Success button |
| `GET` | `/` | Completion stub, `Cache-Control: no-store` |
| `GET` | `/api/auth/me` | Requires `Bearer s0-auth-token`; user `customer_id=e2e-user` |
| `GET` | `/api/auth/device-key/generate` | Requires web Bearer; stores + returns `s1-device-key` |
| `POST` | `/api/auth/device-key/exchange` | Body `{"device_key"}` must match issued; returns native session |
| `GET` | `/api/dashboard` | Requires native access Bearer; trial flags only (prod counterpart provisions the trial) |
| `GET` | `/api/subscriptions` | Requires native access Bearer; one active sub → `/sub/e2e` |
| `GET` | `/sub/e2e` | Minimal valid Clash/Mihomo YAML |
| `POST` | `/api/auth/native/refresh` | Stub; accepts `s1-native-refresh-token` |

Success navigates (Auth Tab completion) to:

```text
https://auth.stage.getline.pro/#/login?auth_token=s0-auth-token&expires_in=300
```

---

## Observability (no full tokens in logs)

Event lines include `source=`:

```text
me_requested source=app|api_smoke bearer_present=… web_token_matches=…
device_key_issued source=… bearer_present=… web_token_matches=… device_key_issued=…
device_key_exchange_succeeded source=… bearer_present=… web_token_matches=… device_key_matches=…
subscriptions_requested source=… native_bearer_present=… native_token_matches=…
subscription_yaml_requested source=…
GET /api/… source=… 12ms
```

| Source | How |
| --- | --- |
| `api_smoke` | Optional request header `X-E2E-Client: api-smoke` (curl smoke only) |
| `app` | Default when header absent (Android and everything else) |

Mock-only. Not required by production RWP. Do not add test headers to production clients.

---

## Local run

```bash
cd tools/e2e-mock
go run .
# or
docker build -t e2e-mock .
docker run --rm -p 8080:8080 e2e-mock
```

### API contract smoke (curl)

```bash
./scripts/smoke-api.sh
# alias: ./scripts/smoke.sh
```

Stage:

```bash
BASE_API=https://app.stage.getline.pro \
BASE_AUTH=https://auth.stage.getline.pro \
./scripts/smoke-api.sh
```

Labeled stages include `[NEGATIVE]` / `[POSITIVE]`. Expected 401/400 on negatives is **success**.  
End line: `API smoke: PASS` or `API smoke: FAIL`. Non-zero exit on failure.

### Android smoke helper (manual Auth Tab)

```bash
# On a host that can reach docker logs for e2e-mock + adb device:
./scripts/watch-android-smoke.sh
# … perform Success on device, press Enter …
# or later:
SINCE=2026-07-29T12:00:00Z ./scripts/watch-android-smoke.sh --check-only
```

Checks server markers for the real app path (filters out `source=api_smoke`).  
Does **not** auto-tap Auth Tab.

---

## Mock limitations

**Mock limitation:** device_key state is **in-process memory**. Restarting `e2e-mock` mid `generate → exchange` invalidates the handoff; re-run login.

---

## Troubleshooting

| Symptom | Likely cause / check |
| --- | --- |
| Auth Tab not supported / no browser | Chrome too old or no Auth Tab category; see browser section. Custom Tabs fallback **not** implemented. |
| `RESULT_VERIFICATION_FAILED` | DAL mismatch: host, package, or cert. Auth Tab completion host must match `GETLINE_CALLBACK_HOST` (`auth.stage.getline.pro` for e2e). |
| Wrong package in DAL | E2E package is `pro.getline.vpn.alpha.e2e.debug`, not `pro.getline.vpn` / `.alpha.debug`. Update `assetlinks.json`. |
| Wrong debug certificate SHA | Fingerprint in DAL must match the keystore that signed the APK. Template: `docs/spikes/android-auth/assetlinks.json.example`. |
| **`Couldn’t sign in` after successful callback** | **Does not prove Auth Tab failure.** Callback already returned. Failure is on **session establishment** (`generate` / `exchange`) or **subscriptions API** — not YAML import (that path shows **ImportFailed**). Check mock logs for the last successful marker. |
| exchange **405** | Wrong HTTP method or path not registered on mock/Caddy. Expect **POST** `/api/auth/device-key/exchange` on **API** host, not GET. |
| Wrong native token on subscriptions (`native_token_matches=false`) | App not sending exchange (or refreshed) access token. Mock accepts fixed `s1-native-access-token` and `s1-native-access-token-refreshed` only — **not** tied to in-memory `device_key`. A mock restart does **not** invalidate those native tokens. |
| **`Couldn’t import the subscription`** / YAML not imported | `ImportFailed` — YAML missing, invalid Clash/Mihomo body, or CMFA validate failed. **Not** AuthFailed. Mock `/sub/e2e` must stay importable (`proxy-groups:`, `rules:`). |
| Caddy cannot reach the mock container | wrong upstream (`127.0.0.1` instead of `e2e-mock:8080`), or the mock is not attached to the proxy network. |
| Stage DNS points at wrong server | both stage hosts must resolve to the edge host, not to the backend host. |

### Important: post-callback “Couldn’t sign in”

UI string `Couldn’t sign in` (`GetLineProductState.AuthFailed`) covers **session handoff** failures after browser return (web token → device-key → native session → subscriptions list), not profile import.

If Auth Tab closed and callback URI was accepted, look at:

1. `GET /api/auth/device-key/generate`
2. `POST /api/auth/device-key/exchange`
3. `GET /api/subscriptions` (including wrong/missing native Bearer)

before blaming Auth Tab or DAL.

Invalid or unavailable subscription **YAML** is a separate UI state: `ImportFailed` (“Couldn’t import the subscription”), via CMFA `Unavailable` — do not treat it as AuthFailed.

---

## Not in S1

OTP, Telegram on stage mock, Custom Tabs fallback, expired/wrong key recovery UX, empty subscriptions list, process death mid Auth Tab, live tunnel quality, plan catalog, payments, device CRUD, full RWP Shop.

Manual subscription-link import **is** in the Android smoke (`flow_subscription_link`); it does not establish a native RWP session.

---

## Related

| Path | Role |
| --- | --- |
| [`docs/spikes/e2e-auth-session-contract.md`](../../docs/spikes/e2e-auth-session-contract.md) | Observed client HTTP contract |
| [`docs/spikes/android-auth/`](../../docs/spikes/android-auth/) | Production Auth Tab / DAL / trampoline notes |
| `main.go` | Mock implementation |
| `scripts/smoke-api.sh` | **API** contract smoke (`[NEGATIVE]`/`[POSITIVE]`; not Android S0) |
| `scripts/smoke.sh` | Thin wrapper → `smoke-api.sh` |
| `scripts/watch-android-smoke.sh` | **Android** S0/S1 marker helper (manual Success) |
| `deploy/*` | Host merge snippets (Caddy + Compose) |
