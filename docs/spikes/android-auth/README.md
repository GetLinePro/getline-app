# Android browser auth deployment notes

## Status (2026-08-09)

| Layer | State |
|---|---|
| **Client (#19 + Telegram native OIDC)** | App-owned PKCE → `GET /api/auth/{provider}/start` with `app_redirect` + S256 challenge → open `auth_url` via Auth Tab / Custom Tabs / `ACTION_VIEW` → callback `${APPLICATION_ID}:/oauth2redirect?code=…` → `POST /api/auth/native/exchange` → native session. No client trampoline; device-key remains for **email OTP only**. |
| **Production backend** | Native PKCE `app_redirect` whitelist: `pro.getline.vpn`, `.alpha`, `.alpha.debug` only (not e2e). **`getline://auth` is not allowed.** Edge trampoline / marker-cookie rewrite still on Caddy as rollback until #22 is green. |
| **Edge HTML ALLOWED** | Same three packages **plus** `pro.getline.vpn.alpha.e2e.debug` (deep-link scheme construction only). Wider than prod backend PKCE list by design of a single HTML file; e2e package on prod edge is inert without a matching backend exchange. |
| **e2e-mock** | API: native exchange (S256), `telegram-oidc/start`, e2e `app_redirect`. **Legacy rollback holes (#22):** stage Caddy is pure `reverse_proxy` to mock — mock has no `/android-auth/telegram` (404) and `GET /` is Auth Tab stub HTML (no `gl_app_id` / package deep-link). These affect the old HTTPS trampoline path, not current native PKCE. |
| **Rollout matrix (#22)** | Device/browser regression after #19; mock static handlers for trampoline + package callback page. |

Backend contract prose: [`../../external/native-auth-flow.md`](../../external/native-auth-flow.md).  
Internal decision trail (untracked): `docs/internal/android-auth-journal.md` (2026-08-06), `docs/internal/spike-native-pkce-2026-08-03.md`.

### Static edge assets (this directory only)

All production browser HTML for auth lives **here** (`docs/spikes/android-auth/`),
next to the trampolines that were already deployable. Not under `tools/e2e-mock/`.

| File in this folder | On server |
|---|---|
| [`auth-callback.html`](auth-callback.html) | `EDGE_WEBROOT/auth-callback.html` → `https://auth.getline.pro/` |
| [`telegram-trampoline.html`](telegram-trampoline.html) | `EDGE_WEBROOT/android-auth/telegram.html` |
| [`google-trampoline.html`](google-trampoline.html) | `EDGE_WEBROOT/android-auth/google.html` (rollback / unused by #19 client) |

Caddy snippet (stage + prod hosts, including how `/` serves `auth-callback.html`):
`private deployment configuration` — path is historical; content is not
“e2e-only”.

---

## Current client path (#19 + Telegram native OIDC) — native PKCE + browser ladder

What the app does now (see Status table above):

| Provider | Launch | Callback |
|---|---|---|
| **Google** | `GET /api/auth/google/start` + app-owned S256 PKCE + `app_redirect` | `{applicationId}:/oauth2redirect?code=…` → `POST /api/auth/native/exchange` |
| **Telegram** | `GET /api/auth/telegram-oidc/start` + app-owned S256 PKCE + `app_redirect` | `{applicationId}:/oauth2redirect?code=…` → `POST /api/auth/native/exchange` |
| **Email** | unchanged | device-key OTP |

Browser capability ladder: Auth Tab → Custom Tabs → external `ACTION_VIEW`.  
External rung resolves a **hostless** `https://` + `CATEGORY_BROWSABLE` package (generic browsers), then `setPackage` on the real launch URI — so a portal WebAPK is not the default target when a browser is installed.  
Auth Tab Google and Telegram both use native scheme completion. Custom Tabs and
external browsers return through the same package callback Activity.

**`auth-callback.html` / trampoline whitelist:** own-property check only (`hasOwnProperty`); packages include `pro.getline.vpn`, `.alpha`, `.alpha.debug`, `.alpha.e2e.debug` (see Status: edge vs backend).

## Why HTTPS completion is not on the portal host

`https://app.getline.pro/manifest.webmanifest` declares `"scope": "/"`. A WebAPK
installed from it becomes a **verified handler for the whole domain** and takes
the completion redirect before the Auth Tab does — this was the 2026-07-28 alpha
incident (one tester had the portal on their home screen; Google finished in the
browser and the app never regained control).

Legacy HTTPS completion lives on a dedicated host that serves only Digital Asset Links and
a static page — no SPA, no web manifest, everything except `/` returns 404. No
WebAPK can claim it. Path stays `/`: `auth.stage.getline.pro` has worked that way
since S0, and `/callback` there is a 404.

The legacy RWP path still redirects to `https://app.getline.pro/#/login?...`,
and asking the vendor to change it is not on our critical path. Instead the Caddy
edge rewrites that one `Location` — see "Callback host rewrite" below. The
current native-PKCE path uses `app_redirect` and does not depend on this hop.

**E2E / stage mock (S0 + S1 green):** synthetic API lives under `tools/e2e-mock/`.  
Observed client contract: [`../e2e-auth-session-contract.md`](../e2e-auth-session-contract.md).  
Runbooks and troubleshooting: [`../../../tools/e2e-mock/README.md`](../../../tools/e2e-mock/README.md).

## Edge history (2026-07-30) — rollback until #22

Shipped on `feat/auth-browser-fallback`. **Prod verified on a device** (Xiaomi /
MIUI, `pro.getline.vpn.alpha.debug`) for the pre-#19 HTTPS Auth Tab + trampoline
path. The current client uses native PKCE for both Google and Telegram; the edge
marker-cookie rewrite and trampoline pages stay deployed as **rollback only**
until #22 is green.

Rollback is therefore code-only: restoring the trampoline path requires a new
client build. That is accepted for this two-user alpha; the deployed Caddy
rewrite and static pages remain available for that build.

| Done (edge, still live) | |
|---|---|
| prod callback host `auth.getline.pro`, path `/` | `GETLINE_CALLBACK_HOST` + `prodAllowedHosts` |
| Caddy edge rewrites provider redirect onto it | marker cookie `gl_native` + `header_down Location` |
| Telegram trampoline | `/android-auth/telegram` — rollback only; current client does not open it |
| Google trampoline | `/android-auth/google` — **rollback only**; #19 client does not open it |
| provider-origin check inside trampolines | static HTML |
| Digital Asset Links on callback hosts | one shared file |
| `subscription_link` not control-plane allowlist | see below |

| Open / next | |
|---|---|
| **#22** regression matrix | Auth Tab / Custom Tab / external / PWA / lifecycle after #19 |
| Deploy edge HTML from this branch | `auth-callback.html` + `telegram-trampoline.html` (hasOwnProperty whitelist + e2e package) |
| e2e Google Auth Tab / native | mock has exchange + S256; `docker compose up -d --build e2e-mock` |
| e2e Custom Tab / external legacy rollback | **broken until #22:** mock needs (1) `/android-auth/telegram` HTML, (2) `GET /` (or auth-callback host path) that reads `gl_app_id` and deep-links — not the current Auth Tab stub |
| web login + PWA-installed regression | smoke items 8–9; package-id callback + external rung vs WebAPK |
| Full removal of Caddy trampoline / marker-cookie | **after** #22 |

### `subscription_link` is not a control-plane host

The import link belongs to RWP and is **not** in `prodAllowedHosts`, so validating
it with `requireProductHttpsUrl` rejected every real production link right after a
successful session (`Protocol: subscription_link not allowed for this
environment`). Use `GetLineControlPlaneHostPolicy.requireSubscriptionUrl`:

- **e2e:** strict `e2eAllowedHosts` — a broken mock must not hand out a prod URL;
- **prod:** any `getline.pro`-family host except stage.

Do not "unify" this back into `isAllowedProductHost`: that predicate also
validates the callback host. E2E cannot catch a regression here — the mock link
lives on `app.stage.getline.pro`, which is in the stage allowlist either way.

`native_state` is deliberately **not** part of the RWP contract on the
**shipped edge path**. For the **#19 native PKCE path**, pending attempt state
(provider, verifier, correlation id) is client-owned; RWP stores server-side
OAuth state and the one-time exchange code.

Longer reasoning, incident history and rejected options live in
`docs/internal/android-auth-journal.md` (untracked, local only).

## Auth methods (client)

| Method   | Path |
|----------|------|
| Telegram | App-owned PKCE → `/api/auth/telegram-oidc/start` → browser ladder → `${APPLICATION_ID}:/oauth2redirect?code=` → `/api/auth/native/exchange` |
| Google   | App PKCE → `/api/auth/google/start` → browser ladder → `${APPLICATION_ID}:/oauth2redirect?code=` → `/api/auth/native/exchange` |
| Email    | In-app OTP → web token → device-key (unchanged) |

`AuthMethod.Email` does **not** use browser auth. Passkey / register intent are
out of scope.

## Provider start (native PKCE)

The app calls start from the process with app-owned PKCE:

```text
GET /api/auth/google/start
  ?intent=register
  &app_redirect=<APPLICATION_ID>:/oauth2redirect
  &code_challenge=<S256>
  &code_challenge_method=S256
→ JSON { "auth_url": "https://accounts.google.com/..." }

GET /api/auth/telegram-oidc/start
  ?intent=register
  &app_redirect=...
  &code_challenge=...
  &code_challenge_method=S256
→ JSON { "auth_url": "https://oauth.telegram.org/..." }
```

- Parameter name is **`app_redirect`** (not `redirect_uri`). Without it the server
  may still return 200 and follow the old web path — unit tests lock the query.
- `code_challenge_method=S256` is always sent explicitly.
- Provider `redirect_uri` (backend ↔ Google/Telegram) stays server-side; the app
  only controls `app_redirect` for the final hop into the package.
- Browser ladder: Auth Tab (`EXTRA_REDIRECT_SCHEME`) → Custom Tabs → `ACTION_VIEW`.
  Auth Tab returns via ActivityResult; the other two via exported
  `NativeAuthCallbackActivity`.

### Email OTP

In-app only (onboarding vertical). No Auth Tab.

```text
POST /api/auth/email/send-otp
Content-Type: application/json
Body: { "email": "<user>" }
→ HTTP 2xx success (optional JSON: expires_in, sent, …)

POST /api/auth/email/verify-otp
Content-Type: application/json
Body: { "email": "<user>", "code": "<otp>", "intent": "login" }
→ JSON { "token": "<webToken>", "expires_in": <seconds>, … }
```

**Client contract (locked):**

- Send body is `{ email }` only (matches web).
- Verify body always hardcodes `"intent": "login"` inside `RwpGetLineAuthApi`
  (not a public API parameter; register / other intents are out of scope).
- HTTP 2xx on send is success; optional `expires_in` is not required for UI.
- Verify returns `token` as the web handoff token (same role as browser
  `auth_token` in the fragment callback).
- Client resend cooldown is **60s** after a successful send (or 429). Server OTP
  TTL is separate (~300s) and is not the resend timer.

**Handoff after verify (same as browser path):**

```text
webToken (verify token / auth_token)
  → establishFromWebToken()
  → device-key generate + exchange
  → native session + preferred subscription import
```

Plain-text error bodies the classifier maps (non-exhaustive): `no_account`,
`email_domain_not_allowed`, `expired`, `too many…`, HTTP 429 → rate limited.
Verify residuals map to invalid OTP.

## Required server-side assets

All auth hosts are served from one shared document root at the edge.

### 1. Digital Asset Links

Auth Tab HTTPS completion verifies domain ownership. Without this file the client
receives `RESULT_VERIFICATION_FAILED`.

```text
EDGE_WEBROOT/.well-known/assetlinks.json
```

Served on `auth.getline.pro`, `auth.stage.getline.pro` and (still) on
`app.getline.pro`. It is **one file for every host** — per-host contents would
need separate roots, which is not worth it while there are three alpha packages
and a single debug key. Editing it "for prod" therefore also edits stage; that
mistake once removed `…alpha.e2e.debug` and broke e2e verification.

Current entries (`assetlinks.json.example`): `pro.getline.vpn.alpha` on the
release key, `…alpha.debug` and `…alpha.e2e.debug` on the machine debug key.
`pro.getline.vpn` (channel `meta`) is absent — add it before publishing.

**A new release keystore means a new fingerprint here.** The release key was
replaced on 2026-07-30 (old one lost); the alpha release build failed sign-in
with `RESULT_VERIFICATION_FAILED` until this file was updated — Auth Tab checks
domain ownership before handing the completion back. Read the fingerprint off the
artifact, not off a keystore dump:

```bash
apksigner verify --print-certs <apk> | grep -i 'SHA-256'
```

The output is lowercase and unseparated; this file needs uppercase, colon-separated.

The debug entries mean anyone holding that debug keystore can build an app that
verifies for these hosts. Acceptable while alpha; drop `…alpha.debug` from the
file once prod is no longer tested from debug builds.

### 2. Provider trampolines (legacy rollback)

The legacy browser start path returns JSON, not a redirect, so its provider needs
a static page. Deploy these **static** rollback documents, which must not be
rewritten to the SPA index:

```text
EDGE_WEBROOT/android-auth/telegram.html   ← telegram-trampoline.html
EDGE_WEBROOT/android-auth/google.html     ← google-trampoline.html
```

Served at `https://app.getline.pro/android-auth/{telegram,google}`. Both paths are
intentionally not `/`, so the initial load cannot be treated as HTTPS completion.
They are retained for rollback only; the current native-PKCE client does not open
them. Until they are deployed, rollback sign-in fails open with a clear error.

The e2e mock serves its own copy of the Google trampoline at the same path
(`tools/e2e-mock/main.go`) for legacy rollback checks. There is no stage Telegram
trampoline page; the mock's current `telegram-oidc/start` API route is separate.

### 3. Callback host rewrite (legacy rollback)

The legacy trampoline flow depends on RWP redirecting to the portal host. The edge
moves that one hop onto the callback host, and only for app logins. The current
native-PKCE flow supplies `app_redirect` to RWP and does not use this rewrite:

1. the trampoline route sets `gl_native=1; Path=/api/auth; Max-Age=120; Secure;
   HttpOnly; SameSite=Lax` — the browser only visits it when the app started the
   flow, so web logins never carry it;
2. requests carrying that cookie are proxied with
   `header_down Location "^https://app\.getline\.pro/#/login"
   "https://auth.getline.pro/#/login"`;
3. everything else is proxied unchanged and keeps landing on the SPA.

The rewrite target is a constant in the config — nothing from the request enters
it, so it cannot become an open redirect. The token stays in the fragment, so it
reaches neither the rewrite nor any access log; `auth.getline.pro` only ever sees
`GET /`.

`Max-Age=120` instead of clearing the cookie on the callback response: doing that
needs `handle_response` machinery for a two-minute window. Cost of not doing it —
if the same browser starts a *web* Google login within those two minutes, it also
lands on the callback host. Self-healing.

Verify without running an OAuth leg:

```bash
P='https://app.getline.pro/api/auth/google/callback?code=x&state=x'
curl -sSI "$P"                        | grep -i location   # app.getline.pro
curl -sSI -H 'Cookie: gl_native=1' "$P" | grep -i location # auth.getline.pro
```

Deploy snippet: `private deployment configuration`.

## Security / log hygiene

- Never log callback URIs, tokens, device keys, subscription URLs, OTP codes, or
  email addresses.
- Persist only native access/refresh tokens and non-secret identity fields.
- Web `auth_token` / email verify `token`, OTP code, and `device_key` are
  memory-only (not SharedPreferences / Intent extras / SavedState).
- OTP digits are cleared from the field on success, back, or leave step; not
  written to logs.

## Regression smoke (manual)

After auth changes, exercise all three entry points on a device/emulator with
network:

1. **Telegram** → Auth Tab complete → session → Home / subscription import.
2. **Google** → Auth Tab complete → session → Home / subscription import.
3. **Email** → send OTP → enter code → session → Home / subscription import.
4. **Email wrong code** → stay on OTP, no session established.
5. **Email resend** → blocked for ~60s after successful send; then allowed.
6. Spot-check logcat during email login: no email, OTP, or token values.

Optional: trigger 429 carefully and confirm wait copy + cooldown UI.

Since completion moved off the portal host, also confirm on a real device:

7. **Telegram native PKCE on prod** — verified 2026-08-09 on physical device
   `707cd278` (`pro.getline.vpn.alpha.debug`), with Telegram installed and
   Chrome Auth Tab. Two successful attempts returned
   `auth_tab_result ... mode=NativeScheme`; the native session completed both
   times.
   Cancellation/back on the consent screen was also exercised successfully;
   only the no-Telegram browser fallback remains open.
8. **Web login unaffected** — sign in through Google in a normal browser tab and
   confirm you land on the SPA, not on the callback page.
9. **PWA installed** — install the portal to the home screen, then sign in from
   the app. This is the incident scenario; it must now succeed.

The legacy edge rollback was verified 2026-07-30 for the pre-native Telegram
path and remains deployed for a rollback build.

## Out of scope

- Passkey / WebAuthn / Credential Manager
- `intent: register` / email registration CTA
- Yandex / VK / Apple
- Post-login tab IA redesign
