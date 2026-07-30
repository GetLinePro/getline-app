# Android browser auth deployment notes

Client implementation uses AndroidX `AuthTabIntent` with HTTPS completion:

- host: from `AppEnvironment.callbackHost` — production `auth.getline.pro`, e2e `auth.stage.getline.pro`
- path: `/`
- fragment: `/login?auth_token=...` (parsed via `Uri.fragment`)

Providers share one Auth Tab launcher and one callback parser. Provider-specific
code only obtains the launch URL.

## Why completion is not on the portal host

`https://app.getline.pro/manifest.webmanifest` declares `"scope": "/"`. A WebAPK
installed from it becomes a **verified handler for the whole domain** and takes
the completion redirect before the Auth Tab does — this was the 2026-07-28 alpha
incident (one tester had the portal on their home screen; Google finished in the
browser and the app never regained control).

So completion lives on a dedicated host that serves only Digital Asset Links and
a static page — no SPA, no web manifest, everything except `/` returns 404. No
WebAPK can claim it. Path stays `/`: `auth.stage.getline.pro` has worked that way
since S0, and `/callback` there is a 404.

RWP still redirects to `https://app.getline.pro/#/login?...`, and asking the
vendor to change it is not on our critical path. Instead the Caddy edge rewrites
that one `Location` — see "Callback host rewrite" below.

**E2E / stage mock (S0 + S1 green):** synthetic API lives under `tools/e2e-mock/`.  
Observed client contract: [`../e2e-auth-session-contract.md`](../e2e-auth-session-contract.md).  
Runbooks and troubleshooting: [`../../../tools/e2e-mock/README.md`](../../../tools/e2e-mock/README.md).

## Status (2026-07-30)

Shipped on `feat/auth-browser-fallback`. **Prod verified on a device** (Xiaomi /
MIUI, `pro.getline.vpn.alpha.debug`): Telegram and Google both complete, import
runs, Home opens.

| Done | |
|---|---|
| prod callback moved to `auth.getline.pro`, path `/` | `GETLINE_CALLBACK_HOST` + `prodAllowedHosts`; parser and `REDIRECT_PATH` untouched |
| Caddy edge rewrites the provider redirect onto it | marker cookie + `header_down Location`; verified by curl **and** on device for both providers |
| Google trampoline | `/android-auth/google`, both providers now launch trampolines |
| provider-origin check inside both trampolines | replaces the `requireBrowserLaunchUrl` check the app can no longer do |
| Digital Asset Links on all callback hosts | one shared file |
| `subscription_link` no longer checked against the control-plane allowlist | see below — this had broken **every** prod import since `c6558428` |
| post-session failures retry only the subscription step | `RetryTarget.ImportPreferredSubscription`; a retry used to re-run the whole browser leg and mint a new device key |

| Open | |
|---|---|
| e2e Google | needs `docker compose up -d --build e2e-mock` |
| e2e Telegram | 404 by decision — the mock has no `telegram-oidc/start` |
| web login unaffected + PWA-installed regression | smoke items 8 and 9 below, still unrun |
| protected auth attempt (`native_state`), App Link ingress, Auth Tab → Custom Tab → ACTION_VIEW ladder, cancel/timeout UX | not started; the fallback ladder is what introduces the exported callback Activity |

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

`native_state` is deliberately **not** part of the RWP contract. When the attempt
store needs it, mint it at the edge — the vendor is not on our critical path.

Longer reasoning, incident history and rejected options live in
`docs/internal/android-auth-journal.md` (untracked, local only).

## Auth methods (client)

| Method   | Path on client                         | Notes                                      |
|----------|----------------------------------------|--------------------------------------------|
| Telegram | Auth Tab + trampoline                  | Browser cookies for PKCE                   |
| Google   | Auth Tab after `GET .../google/start`  | App process fetches `auth_url`             |
| Email    | In-app OTP (no Auth Tab)               | `send-otp` → `verify-otp` → web token      |

`AuthMethod.Email` does **not** use browser auth. Passkey / register intent are
out of scope.

## Provider start

Neither start endpoint is called from the app process. Both providers launch a
same-origin trampoline on the portal host, which calls start in-browser. The app
only knows two URLs (`AppEnvironment.googleTrampolineUrl` /
`telegramTrampolineUrl`).

Because the app no longer sees `auth_url`, the host check that
`requireBrowserLaunchUrl` used to perform moved into the trampoline HTML: each
one refuses to navigate anywhere but its provider's origin.

### Google

```text
GET /api/auth/google/start → JSON { "auth_url": "https://accounts.google.com/..." }
```

Google start works from any client, but the browser must visit the portal origin
first so the edge can set the marker cookie that scopes the callback rewrite to
app logins. Hence `/android-auth/google`.

`redirect_uri` is fixed server-side to
`https://app.getline.pro/api/auth/google/callback` — the app cannot influence it.

### Telegram

```text
GET /api/auth/telegram-oidc/start?intent=login&return_to=...
→ JSON { "auth_url": "https://oauth.telegram.org/..." }
```

Telegram start sets HttpOnly PKCE cookies. Those cookies must be stored in the
Auth Tab browser jar, so start must run in the browser. `return_to` stays the
portal root — the edge rewrite moves the final hop, so nothing here needs to know
about the callback host.

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

### 2. Provider trampolines

Both start endpoints return JSON, not a redirect, so each provider needs a static
page. Deploy as **static** documents that are not rewritten to the SPA index:

```text
EDGE_WEBROOT/android-auth/telegram.html   ← telegram-trampoline.html
EDGE_WEBROOT/android-auth/google.html     ← google-trampoline.html
```

Served at `https://app.getline.pro/android-auth/{telegram,google}`. Both paths are
intentionally not `/`, so the initial load cannot be treated as HTTPS completion.
Until they are deployed, sign-in fails open with a clear error.

The e2e mock serves its own copy of the Google trampoline at the same path
(`tools/e2e-mock/main.go`), so both flavors exercise one client path. There is no
stage Telegram trampoline — the mock has no `telegram-oidc/start` route.

### 3. Callback host rewrite (Google + Telegram)

RWP redirects to the portal host. The edge moves that one hop onto the callback
host, and only for app logins:

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

7. **Telegram on prod** — done 2026-07-30. The rewrite is matched on
   `path /api/auth/*`; both providers work with it, so RWP emits the Telegram
   redirect from under `/api/auth/` too. If that ever regresses, widen the
   matcher to all paths — the cookie's own `Path=/api/auth` already scopes it.
8. **Web login unaffected** — sign in through Google in a normal browser tab and
   confirm you land on the SPA, not on the callback page.
9. **PWA installed** — install the portal to the home screen, then sign in from
   the app. This is the incident scenario; it must now succeed.

## Out of scope

- Passkey / WebAuthn / Credential Manager
- `intent: register` / email registration CTA
- Yandex / VK / Apple
- Post-login tab IA redesign
