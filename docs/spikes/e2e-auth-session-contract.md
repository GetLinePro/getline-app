# E2E auth/session API contract (S0 + S1)

Observed client contract for **alphaE2eDebug** against stage mock
(`app.stage.getline.pro` / `auth.stage.getline.pro`).

This document describes what the **current Android client actually does**, not
the original spike assumptions. Prefer this over outdated fragments in
`docs/spikes/cmfa-feasibility.md` when they conflict.

## Evidence legend

| Tag | Meaning |
| --- | --- |
| **Observed** | Confirmed by app code, unit tests, mock code, smoke script, and/or live device run. |
| **Expected** | Requirement of the e2e test system (mock + smoke + device acceptance). |
| **Not covered** | Not verified yet; do not invent. |

Every substantial claim below cites a file path and line range.

---

## Flavors and origins

| Dimension | Values | Notes |
| --- | --- | --- |
| `channel` | `alpha`, `meta` | Root product flavors. |
| `environment` | `prod`, `e2e` | App-module only (`app/build.gradle.kts`). |

**Working variant:** `alphaE2eDebug`  
**applicationId:** `pro.getline.vpn.alpha.e2e.debug`

| BuildConfig | e2e value | Source |
| --- | --- | --- |
| `GETLINE_API_ORIGIN` | `https://app.stage.getline.pro` | `app/build.gradle.kts` L53–57 |
| `GETLINE_AUTH_ORIGIN` | `https://auth.stage.getline.pro` | L58–62 |
| `GETLINE_CALLBACK_HOST` | `auth.stage.getline.pro` | L63–67 |
| `GETLINE_PORTAL_ORIGIN` | `https://app.stage.getline.pro` | L68–72 |

Runtime access: `AppEnvironment.apiOrigin` / `authOrigin` / `callbackHost`
(`app/src/main/java/pro/getline/vpn/AppEnvironment.kt` L11–22).

`RwpGetLineAuthApi` defaults `origin` to `AppEnvironment.apiOrigin`
(`RwpGetLineAuthApi.kt` L14–16). All JSON auth/subscription API paths below
hit **API origin**, not auth origin.

Auth Tab completion host is **callback host** (auth stage for e2e)
(`AuthCallbackParser.kt` L57–58; `BrowserAuthLauncher` uses
`AppEnvironment.callbackHost`).

e2e is enabled only for `alpha` + `debug`
(`app/build.gradle.kts` L81–94).

### Control-plane host isolation (Observed)

**Expected** for e2e: product layer must not open or call production RWP/Auth
hosts even if a mock response is wrong.

Policy: `GetLineControlPlaneHostPolicy`
(`app/src/main/java/pro/getline/vpn/GetLineControlPlaneHostPolicy.kt`).

| Environment | Allowed product hosts |
| --- | --- |
| e2e | `app.stage.getline.pro`, `auth.stage.getline.pro` |
| prod | `app.getline.pro` |

Applied to: API origin, browser `auth_url`, callback host, portal,
`subscription_link` from subscriptions API (import path).

**Not covered:** Mihomo/VPN proxy endpoints after import; manual user URL
import outside the post-login preferred-subscription path.

---

## End-to-end sequence (happy path)

**Observed** device flow (alphaE2eDebug):

```text
GET  /api/auth/google/start          (API origin, no token)
     → open auth_url in Auth Tab     (auth stage mock Google)
     → HTTPS callback + DAL          (auth.stage.getline.pro fragment)
     → parse web auth_token          (memory only)
GET  /api/auth/me                    (web Bearer; failure non-fatal)
GET  /api/auth/device-key/generate   (web Bearer → device_key)
POST /api/auth/device-key/exchange   (body device_key; no Bearer)
GET  /api/subscriptions              (native access Bearer)
GET  <subscription_link>             (CMFA/Clash fetch; no auth Bearer)
     → YAML import → activate → Home
     → force-stop → relaunch → saved session/profile
```

Orchestration:

| Step | Code |
| --- | --- |
| Browser start | `RwpGetLineAuthApi.startBrowserAuth` L17–36 |
| Callback parse | `AuthCallbackParser.parse` L20–41 |
| Web → native | `GetLineSessionRepository.establishFromWebToken` L23–34 |
| Preferred sub | `loadPreferredSubscription` L71–75 → `getSubscriptionsAuthenticated` L135–161 |
| Import URL | `GetLineOnboardingActivity.completeLoginFromWebToken` L399–432 |
| YAML download | `Clash.fetchAndValid` → Go `openUrl` `http.MethodGet` |

**Not covered:** asserting that first-login path calls `POST /api/auth/native/refresh`.
Code path does not call refresh inside `establishFromWebToken`. Live log
refresh traffic during early smoke may be the curl smoke script step 14
(`tools/e2e-mock/scripts/smoke.sh` L194–206), not the app.

---

## Shared HTTP client rules

**Observed** (`RwpGetLineAuthApi.request` L135–206):

| Rule | Behavior |
| --- | --- |
| Base URL | `{AppEnvironment.apiOrigin}{path}` |
| Success | HTTP 200–299 + non-empty JSON body (unless `allowEmptyBody`) |
| Errors | Non-2xx → `GetLineAuthErrorClassifier.classify` → usually `HttpFailure(code, body)` |
| Timeout | connect/read 30_000 ms (`TIMEOUT_MS` L247) |
| Always | `Accept: application/json` |
| If `xhr` | `X-Requested-With: XMLHttpRequest` |
| If `includeBrowserOriginHeaders` | `Origin: {apiOrigin}`, `Referer: {apiOrigin}/` |
| If `bearer != null` | `Authorization: Bearer {token}` |
| If body | `Content-Type: application/json` + POST body |

---

## Endpoints

### 1. `GET /api/auth/google/start`

| | |
| --- | --- |
| **Method / path** | `GET` `/api/auth/google/start` |
| **Origin** | API (`AppEnvironment.apiOrigin`) |
| **Headers (client)** | `Accept: application/json` only (`xhr=false`, no Origin/Referer, no Bearer) — L22–28, L151 |
| **Request body** | none |
| **Token** | none |
| **Success response** | `{"auth_url":"<https url>"}` — required non-blank `auth_url` L30–34 |
| **Required fields** | `auth_url` |
| **Optional fields** | none used |
| **HTTP success** | 2xx |
| **Client on failure** | exception → AuthFailed UI |
| **Tolerates absence?** | **No** — required to open Auth Tab |
| **Next step** | Launch `auth_url` in Auth Tab; wait for HTTPS callback |

**Observed mock:** returns
`{"auth_url":"https://auth.stage.getline.pro/__mock__/google"}`
(`tools/e2e-mock/main.go` L104–108).

**Expected (e2e):** start always points Auth Tab at mock Google, never production
OAuth.

**Auth Tab completion (not an API call):** fragment

```text
https://auth.stage.getline.pro/#/login?auth_token=s0-auth-token&expires_in=300
```

Parser requires scheme `https`, host = `callbackHost`, path `/`, fragment
`/login?...` with `auth_token` (`AuthCallbackParser.kt` L16–18, L49–78).
Fragment is not sent to the server (`main.go` L118 comment).

---

### 2. `GET /api/auth/me`

| | |
| --- | --- |
| **Method / path** | `GET` `/api/auth/me` |
| **Origin** | API |
| **Headers** | `Accept: application/json`, `Authorization: Bearer <webToken>` — `authorizedGet` L120–132; `xhr=false` (no X-Requested-With, no Origin/Referer) |
| **Request body** | none |
| **Token** | **Web** `auth_token` from callback |
| **Success response (fields read)** | optional strings: `customer_id`, `username`, `first_name`, `telegram_id` (string or number), `role` — L66–75 |
| **Required fields for client** | **none** — all mapped with `optStringOrNull` |
| **HTTP success** | 2xx with JSON object |
| **Tolerates absence / failure?** | **Yes** — `runCatching { getCurrentUser }.getOrNull()`; non-fatal (`GetLineSessionRepository.kt` L24–28) |
| **Next step** | Always continues to device-key generate regardless of me result |

**How response is used (code):**

```text
getCurrentUser(webToken)
  → if user.customerId != null → store.customerId = customerId
  → username / first_name / telegram_id / role are parsed but not persisted
    and not referenced elsewhere in app code (grep: only write path for customerId)
```

Sources: `GetLineSessionRepository.kt` L23–28; `GetLineSessionStore.kt` L52–54;
`RwpGetLineAuthApi.kt` L66–75.

**S0 vs S1:**

| Slice | Behavior |
| --- | --- |
| S0 | Client could receive 404/401; handoff still proceeds because me is optional. |
| S1 mock | Returns 200 + synthetic user when Bearer is `s0-auth-token` (`main.go` L130–156). Wrong/missing Bearer → 401. |

**Expected (e2e S1):** mock serves valid synthetic user for web token so
`customer_id=e2e-user` can be stored when probe succeeds.

**Observed smoke:** wrong token → 401; good token → `customer_id=e2e-user`
(`scripts/smoke.sh` L79–93).

---

### 3. `GET /api/auth/device-key/generate`

| | |
| --- | --- |
| **Method / path** | `GET` `/api/auth/device-key/generate` |
| **Origin** | API |
| **Headers** | `Accept: application/json`, `Authorization: Bearer <webToken>`, `X-Requested-With: XMLHttpRequest` (`xhr=true`) — L78–83 |
| **Request body** | none |
| **Token** | **Web** Bearer |
| **Success response** | `{"device_key":"<string>"}` — `device_key` required non-null/non-blank L84–86 |
| **Required fields** | `device_key` |
| **HTTP success** | 2xx |
| **Tolerates absence?** | **No** — failure aborts handoff; session not saved (`BrowserAuthHandoffTest` L39–55) |
| **Next step** | `POST /api/auth/device-key/exchange` with returned key |

**Observed:** generate is called with the same web token string used for me
(`establishFromWebToken` L30; handoff test asserts `lastGenerateBearer` L34).

**Expected (e2e):** mock validates web Bearer (`s0-auth-token`), stores issued
key in process memory, returns `s1-device-key` (`main.go` L158–191). Wrong
Bearer → 401.

---

### 4. `POST /api/auth/device-key/exchange`

| | |
| --- | --- |
| **Method / path** | `POST` `/api/auth/device-key/exchange` |
| **Origin** | API |
| **Headers** | `Accept: application/json`, `Content-Type: application/json`, `X-Requested-With: XMLHttpRequest`, `Origin: {apiOrigin}`, `Referer: {apiOrigin}/` — L89–98 |
| **Authorization** | **Not sent** (`bearer = null` L94) |
| **Request body** | exact JSON: `{"device_key":"<value from generate>"}` — L90 |
| **Token** | none (device_key in body is the credential) |
| **Success response** | `access_token` (required), `refresh_token` (required), `expires_in` (optional; default 86400 if missing) — `toNativeSession` L208–221 |
| **Required fields** | `access_token`, `refresh_token` |
| **Optional fields** | `expires_in` (seconds) |
| **HTTP success** | 2xx |
| **Tolerates absence?** | **No** — native session not established |
| **Next step** | `store.saveSession(session)` then subscriptions load |

**Observed live/mock note:** exchange succeeds with
`bearer_present=false` and `device_key_matches=true`. Do **not** require Bearer
on this endpoint for app compatibility (`main.go` L193–195, L235–236;
README “Exchange note”).

Mock rules:

- body `device_key` must equal last issued key → else 400
- if Bearer **present and wrong** → 401 (controlled; app does not send Bearer)
- success → fixed native pair `s1-native-access-token` / `s1-native-refresh-token`,
  `expires_in: 3600` (`main.go` L270–275)

**Expected (e2e):** smoke and device path call exchange **without** Authorization
(`smoke.sh` L124–133).

---

### 5. `GET /api/subscriptions`

| | |
| --- | --- |
| **Method / path** | `GET` `/api/subscriptions` |
| **Origin** | API |
| **Headers** | `Accept: application/json`, `Authorization: Bearer <native access>` — L115–117, authorizedGet |
| **Request body** | none |
| **Token** | **Native access** from exchange (or later refresh) |
| **Success response** | JSON parsed by `SubscriptionsJson.parseResponse` |
| **HTTP success** | 2xx |
| **Tolerates absence?** | **No** for first login import path (`loadPreferredSubscription` throws if no importable item) |
| **Next step** | `selectPreferred()` → import `subscription_link` URL |

**Response shape (client):**

Top-level:

| Field | Required? | Notes |
| --- | --- | --- |
| `autopay_available` | optional | default `false` (`SubscriptionsJson.kt` L23) |
| `subscriptions` | optional array | missing → empty list L15–16 |

Per item (all optional for parse; **import needs a non-blank link**):

| Field | Client use |
| --- | --- |
| `subscription_link` (aliases: `subscription_url`, `url`, `link`) | Import URL — preferred selection requires non-blank (`GetLineAuthModels.kt` L66–70) |
| `id` | stringified; profile reuse matching |
| `is_primary` / `primary` | prefer primary with link |
| `name`, `plan_name` | profile display name |
| `is_active`, `kind`, `plan_type`, `expire_at`, `days_left`, limits, traffic, flags | UI / presentation; not required for S1 import |

Selection algorithm (`SubscriptionsResponse.selectPreferred` L66–70):

1. First item with non-blank link **and** `isPrimary`
2. Else first item with non-blank link

**401 recovery (client):** one forced `refresh` then single retry; second 401
logs out (`GetLineSessionRepository.kt` L135–160).

**Expected (e2e):** mock rejects wrong native Bearer (401); valid native token
returns one active primary sub with
`subscription_link=https://app.stage.getline.pro/sub/e2e` (`main.go` L278–329;
`smoke.sh` L146–170).

---

### 6. `GET <subscription_link>` (YAML import)

| | |
| --- | --- |
| **Method** | **`GET`** — confirmed in Clash native fetch, not guessed |
| **Path** | Absolute URL from subscription item (e2e: `https://app.stage.getline.pro/sub/e2e`) |
| **Origin** | Host inside the link (API stage for mock) |
| **Headers** | `User-Agent: ClashMetaForAndroid/{app version}` only (`core/src/main/golang/native/config/fetch.go` L41–44) |
| **Authorization** | **none** |
| **Request body** | none |
| **Success** | HTTP success body = Clash/Mihomo YAML; optional headers `Subscription-Userinfo`, `Profile-Update-Interval` read if present L50–53 |
| **Tolerates absence?** | **No** — import fails |
| **Next step** | CMFA validate/commit/activate → Home |

**Evidence chain:**

1. Onboarding sets `GetLineSubscriptionDraft.source = subscription.subscriptionLink`
   (`GetLineOnboardingActivity.kt` L409–417)
2. Import goes through CMFA `importAndCommit` → `Clash.fetchAndValid`
3. Go `openUrl` uses `http.MethodGet` (`fetch.go` L44)

Mock serves minimal valid YAML on `GET /sub/e2e` (`main.go` L334–343, L424–445).
Smoke checks YAML contains `proxy-groups:` and `rules:` (`smoke.sh` L184–191).

---

### 7. `POST /api/auth/native/refresh`

| | |
| --- | --- |
| **Method / path** | `POST` `/api/auth/native/refresh` |
| **Origin** | API |
| **Headers** | same browser-style set as exchange: Accept, Content-Type, X-Requested-With, Origin, Referer — L102–111 |
| **Authorization** | **Not sent** |
| **Request body** | exact JSON: `{"refresh_token":"<stored refresh>"}` — L103 |
| **Token** | refresh token in body |
| **Success response** | same native session shape as exchange (`toNativeSession`) |
| **Required fields** | `access_token`, `refresh_token` |
| **Optional** | `expires_in` (default 86400) |
| **HTTP success** | 2xx |
| **When client calls** | (1) `validAccessToken()` if access expired; (2) one-shot 401 recovery in `getSubscriptionsAuthenticated` / `forceRefreshSession` L44–63, L163–171 |
| **First login?** | **Not observed in app first-login path** — `establishFromWebToken` never calls refresh |

**Expected (e2e):** mock stub accepts `s1-native-refresh-token` and returns
refreshed pair (`main.go` L345–377). Smoke exercises the stub explicitly
(`smoke.sh` L194–206) — that is **not** proof the app hit refresh during onboarding.

**Not covered:** refresh rotation semantics under concurrent 401s on device;
expired native session relaunch UX beyond unit tests
(`SubscriptionLoadRepositoryTest`).

---

## Token roles (synthetic e2e)

| Role | Value | Used by |
| --- | --- | --- |
| Web auth_token | `s0-auth-token` | me, generate (Bearer) |
| Device key | `s1-device-key` | exchange body only |
| Native access | `s1-native-access-token` | subscriptions Bearer |
| Native refresh | `s1-native-refresh-token` | refresh body (recovery/stub) |
| After refresh | `s1-native-access-token-refreshed` / `…-refreshed` | mock only |

Source: `tools/e2e-mock/main.go` L22–36; README tokens table.

**Observed:** client never stores web token or device_key in session prefs
(`GetLineSessionStore` KDoc L9–11).

---

## Client flow map (first login)

```text
startBrowserAuth(Google)
  → Auth Tab → AuthCallbackParser → webToken (memory)
completeLoginFromWebToken
  → establishFromWebToken
       getCurrentUser        [optional]
       generateDeviceKey     [required, web Bearer]
       exchangeDeviceKey     [required, body device_key, no Bearer]
       saveSession           [native tokens]
  → loadPreferredSubscription
       validAccessToken      [uses access; refresh only if expired]
       getSubscriptions      [native Bearer]
       selectPreferred
  → importAndCommit(subscription_link)
       GET YAML via Clash
  → rememberManagedProfile + rememberSubscription
  → Home
```

Sources: `GetLineOnboardingActivity.kt` L399–432; `GetLineSessionRepository.kt`
L23–34, L71–75, L135–161.

---

## Observed vs Expected vs Not covered (summary)

### Observed

- Full method/path/header/body matrix for start, me, generate, exchange,
  subscriptions, refresh from `RwpGetLineAuthApi`.
- me is optional; only `customer_id` is stored if present.
- exchange sends **no** Bearer; body field name is `device_key`.
- subscriptions require native access Bearer; prefer primary with link.
- YAML fetch is **HTTP GET** with ClashMeta User-Agent, no auth header.
- Device happy path through Home + kill/relaunch with saved profile
  (project acceptance notes / e2e-mock README).
- Smoke script covers positive and negative cases for mock endpoints
  (false→true transitions in server logs can be smoke negatives, not app retries).

### Expected (e2e system)

- Mock implements the paths above with fixed synthetic tokens.
- Mock rejects wrong web token on me/generate and wrong native token on subscriptions.
- Mock accepts exchange without Bearer when `device_key` matches last generate.
- `/sub/e2e` returns importable YAML.
- Production RWP Shop / production API are not used for alphaE2eDebug.

### Not covered

- App calling native refresh during **first** cold login (code says no; need
  separate access-log proof if claimed).
- Telegram / email OTP on stage mock (explicitly out of S1 mock surface).
- Empty subscriptions list UX on e2e.
- Device-key TTL/replay beyond mock “last issued key” memory.
- Full production RWP field parity for `/api/me` beyond fields the client reads.
- VPN tunnel connectivity (S1 stops at import/activate/Home).

---

## Slice status (foundation close-out)

| Slice | Status |
| --- | --- |
| S0 Auth Tab handoff | **green** (shipped path: web token → device-key) |
| S1 Native session + subscription import | **green** |
| S2 Email OTP | not started |
| Custom Tabs / native PKCE (`${APPLICATION_ID}:/oauth2redirect`) | **not in this mock yet** — product #19; matrix #22. e2e package intends `pro.getline.vpn.alpha.e2e.debug:/oauth2redirect` (mock/stage, not prod RWP). Contract body above still describes the shipped edge path. |

How to run the mock, repeat S0/S1 on device, browser notes, and troubleshooting:  
[`tools/e2e-mock/README.md`](../../tools/e2e-mock/README.md).

---

## Related artifacts

| Artifact | Role |
| --- | --- |
| `tools/e2e-mock/main.go` | Stage mock implementation |
| `tools/e2e-mock/README.md` | Deploy, S0/S1 runbooks, troubleshooting |
| `tools/e2e-mock/scripts/smoke.sh` | Curl contract smoke (includes refresh stub) |
| `app/src/main/java/pro/getline/vpn/getline/auth/RwpGetLineAuthApi.kt` | Live client HTTP |
| `app/src/main/java/pro/getline/vpn/getline/auth/GetLineSessionRepository.kt` | Session orchestration |
| `app/src/main/java/pro/getline/vpn/getline/auth/SubscriptionsJson.kt` | Subscriptions DTO mapping |
| `core/src/main/golang/native/config/fetch.go` | YAML GET |
| `docs/spikes/android-auth/` | Production DAL / trampoline notes |
| `docs/spikes/cmfa-feasibility.md` | Historical spike (may lag client) |
| `docs/spikes/subscription-api-contracts.md` | Broader catalog/billing API notes |

When updating mock behavior, update this file and `tools/e2e-mock/README.md` in the same change.
