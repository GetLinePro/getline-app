# Android browser auth deployment notes

Client implementation uses AndroidX `AuthTabIntent` with HTTPS completion:

- host: `app.getline.pro`
- path: `/`
- fragment: `/login?auth_token=...` (parsed via `Uri.fragment`)

Providers share one Auth Tab launcher and one callback parser. Provider-specific
code only obtains the launch URL.

## Auth methods (client)

| Method   | Path on client                         | Notes                                      |
|----------|----------------------------------------|--------------------------------------------|
| Telegram | Auth Tab + trampoline                  | Browser cookies for PKCE                   |
| Google   | Auth Tab after `GET .../google/start`  | App process fetches `auth_url`             |
| Email    | In-app OTP (no Auth Tab)               | `send-otp` → `verify-otp` → web token      |

`AuthMethod.Email` does **not** use browser auth. Passkey / register intent are
out of scope.

## Provider start

### Google

```text
GET /api/auth/google/start → JSON { "auth_url": "https://accounts.google.com/..." }
```

The app fetches this endpoint, then opens `auth_url` in the Auth Tab. Google
start does not require browser cookies for the OAuth leg.

### Telegram

```text
GET /api/auth/telegram-oidc/start?intent=login&return_to=...
→ JSON { "auth_url": "https://oauth.telegram.org/..." }
```

Telegram start sets HttpOnly PKCE cookies (`tg_oidc_verifier`, `tg_oidc_state`).
Those cookies must be stored in the Auth Tab browser jar, so the app does **not**
call the start endpoint from the app process. Instead it opens the same-origin
trampoline below, which performs start in-browser and navigates to `auth_url`.

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

### 1. Digital Asset Links

Auth Tab HTTPS completion verifies domain ownership.

Deploy as:

```text
https://app.getline.pro/.well-known/assetlinks.json
```

Use `assetlinks.json.example` as a template. Fill release certificate SHA-256
fingerprints for every shipping `applicationId` (meta/alpha and debug suffixes
as needed).

Without this file, the client receives `RESULT_VERIFICATION_FAILED`.

### 2. Telegram one-tap trampoline

`/api/auth/telegram-oidc/start` returns JSON, not a redirect. Deploy
`telegram-trampoline.html` as a **static** document on a non-`/` path, for
example:

```text
https://app.getline.pro/android-auth/telegram
```

The path must not be rewritten to the SPA index.

The Android Telegram entry point starts Auth Tab at:

```text
https://app.getline.pro/android-auth/telegram
```

That path is intentionally not `/`, so the initial load cannot be treated as
HTTPS completion. Until the trampoline HTML is deployed as static content (not
rewritten to the SPA), Telegram sign-in will fail open with a clear error —
deploy the file before relying on device login.

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

## Passkey recon (not implementing)

Live (2026-07): `GET /api/auth/passkey/status` → `enabled: true`. SPA has Passkey
button + `begin`/`finish` (rpId `app.getline.pro`, discoverable login).

**Gap:** after `POST /api/auth/passkey/login/finish` the SPA only applies the
token in-memory (`Tt({token,…})` + `onLoginSuccess`). It does **not** navigate to
`#/login?auth_token=…`. OAuth is the only path that puts `auth_token` in the
fragment for Auth Tab. Opening general web login is also a bad start URL (path
`/` = Auth Tab completion host/path).

So Android cannot reuse browser auth for free. Options later:

1. **RWP change** — finish → `#/login?auth_token=…` (+ non-`/` trampoline) → thin
   Android Auth Tab entry.
2. **Native** — Credential Manager + same begin/finish; backend must accept
   Android origin / apk-key-hash; expand `assetlinks.json` beyond alpha.debug.
3. **Skip v1** — TG + Google + Email already cover acquisition.

Not proven live: finish token → device-key (same shape as email/OAuth token in
SPA, but no sample run). Desktop URL after Passkey success still HITL.

## Out of scope

- Passkey / WebAuthn / Credential Manager (see recon above)
- `intent: register` / email registration CTA
- Yandex / VK / Apple
- Post-login tab IA redesign
