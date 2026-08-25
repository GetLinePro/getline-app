# GetLine subscription profile contract

**Decided:** 2026-08-11, restated 2026-08-13. HWID request headers: 2026-08-25.
Android package exclusions: 2026-08-25.
**Status:** in effect. Issue #26.

How the client asks for a profile, which response markers identify the GetLine
variant, and what happens when the panel answers with something else.

The live YAML template lives in the panel and is not copied here.

## User-Agent

First import and refresh send:

```http
User-Agent: GetLineVPN/<versionName>
```

`versionName` is the installed package version, or `unknown` if it is missing.

The panel matches `^GetLineVPN(?:/|$)` (case-insensitive) and returns the GetLine
Mihomo template. Other clients keep the broader Clash template.

The token selects a format. It is not authentication and not a trust boundary.

HTTPS URL profiles use the Kotlin downloader (`PrimaryConfigDownloader`). Other
schemes, and rule/proxy providers, go through `core/.../native/config/fetch.go`.
Providers ignore this User-Agent.

## Device identifier

Primary HTTPS fetch also sends Remnawave HWID headers:

```http
x-hwid: <app-generated UUID>
x-device-os: Android
x-ver-os: <Build.VERSION.RELEASE>
x-device-model: <Build.MODEL>
```

`x-hwid` is a random UUID generated once in the app and stored in service
preferences. It is not derived from `ANDROID_ID` or any other device identifier.
If the stored value is blank after generate-and-persist, all four headers are
omitted for that request.

These headers follow `User-Agent` on redirects: sent on every allowed hop
(same host; cross-host is rejected). They are not gated on same-origin
(host+port) like `Authorization` / `If-None-Match`.

Do not log the HWID value. Rule/proxy provider fetch (`fetch.go`) does not send
these headers.

## Response markers

Expected on the GetLine projection:

```http
Content-Type: text/yaml; charset=utf-8
X-GetLine-Profile: subscription
X-GetLine-Schema: 1
X-GetLine-Tag: …
X-GetLine-Status: …
X-GetLine-Device-Limit: 10
ETag: W/"…"
Vary: Origin, Accept-Encoding, User-Agent
```

`x-getline-schema: 1` is the current schema. A later number means a capability
this client may not apply. The client does not parse or enforce the number.

`X-GetLine-Tag`, `X-GetLine-Status`, and `X-GetLine-Device-Limit` are optional
user attributes. The device limit is displayed only when it is a positive
integer; the current device count is not read or displayed. After a
successful primary-config fetch (`200` or `304`) the client stores them on the
imported profile and builds the Subscription card from that snapshot. A
successful response that omits a header clears the saved value. A failed fetch
leaves the last snapshot. Do not log these values.

The YAML body may also carry an `x-getline-profile` mapping. That body key is
not the response header. Presence or absence of `X-GetLine-Profile` does not
enable or disable parsing of the YAML extension. Judge the answering template
by the headers.

Do not log `profile-web-page-url`. The panel puts the subscription token in that
URL path.

## Three identifiers named GetLine profile

These are different layers. Do not treat them as one gate.

1. Request `User-Agent: GetLineVPN/<versionName>` selects the backend projection.
   It is format selection, not authentication.
2. Response header `X-GetLine-Profile` is diagnostic. The client logs which
   template answered. It does not choose the projection and is not a reject-gate.
3. YAML body key `x-getline-profile` is an optional extension namespace in the
   profile body. It is not authentication. The profile already supplies proxies
   and rules. This field only names Android packages that stay off the VPN
   interface and use the ordinary system network.

`schema` and `kind` under `x-getline-profile`, if present, are ignored. They are
not a compatibility gate and do not bump `schema: 1_1`.

## Android package exclusions

Optional:

```yaml
x-getline-profile:
  android:
    excluded-packages:
      - com.example.maps
```

Normalization:

- Field absent → empty set. A valid `android-policy.json` sidecar is still
  written: `{"version":1,"excludedPackages":[]}`.
- Present sequence of strings → trim, drop duplicates, keep first-seen order.
- Explicit `null`, a scalar, a mapping, a non-string element, or an
  empty-after-trim element → preparation fails. The last committed imported
  profile is kept. No TUN reconcile runs.

The sidecar is a security input, not a degradable read model. After a
successful core parse the native path writes it atomically
(`android-policy.json.tmp` → `android-policy.json`, mode `0600`). A write
failure rejects preparation. `server-catalog.json` stays best-effort.

The running service reads the sidecar of the active profile. A missing file on
a UUID never seen in this `TunService` lifetime is a legacy profile: empty
policy, until the next successful refresh/reimport writes a sidecar. For a UUID
already observed, a temporarily missing or unreadable sidecar (including a torn
copy during sequential imported-directory commit) keeps the in-memory
last-known-good and logs a warning. Unknown version, malformed JSON, or an
invalid package entry fails closed only when this process has no last-known-good
for that UUID.

Effective composition is in `AccessControlPlan`, not Mihomo routing:

- All apps: `disallowed = subscriptionExcluded`
- All except selected: `disallowed = userExcluded ∪ subscriptionExcluded`
- Only selected: `allowed = (userAllowed ∪ ownPackage) − subscriptionExcluded`

GetLine's own package is removed from subscription exclusions with a warning.
Uninstalled packages stay in the declared plan; `VpnService.Builder` already
skips `NameNotFoundException` until the next ordinary rebuild. There is no
package install listener.

A successful import, refresh, activation, or switch of the active profile
triggers the existing TUN reconcile. An inactive-profile update, or a 304 whose
effective plan is unchanged, is a no-op.

This field is not a Mihomo `tun` or inbound control, and it does not set
`VpnService.Builder.allowBypass()`. Package names are not routing rules.

## Compatibility

- The panel must not send a capability a given client version cannot apply.
  The dangerous case is an unknown proxy type: the core rejects the whole
  configuration, not one node. The same rollout order applies to a non-empty
  `excluded-packages` list: backend first learns to emit the field as
  absent/empty, Android support ships, then non-empty exclusions.
- A new install has no last-known-good profile; if the first import is rejected
  there is nothing to keep.
- Missing or unexpected **headers** are a diagnostic signal. A parseable
  profile is still applied.
- Reject the update on unparseable YAML, a non-2xx / transport failure, a core
  apply failure, or a malformed **declared** `x-getline-profile.android.excluded-packages`
  field (including a sidecar write failure after a successful parse).
- On those hard failures the last committed imported profile stays. ETag is
  written only after validate succeeds. `304` is accepted only when a local body
  exists; otherwise the client retries once without `If-None-Match`.

`ProfileProcessor.update` copies `processing/` onto `imported/` only after
fetch/validate returns. A failure in between leaves the imported snapshot
untouched even if `processing/config.yaml` was already overwritten.

That imported-dir invariant is sequential control flow around the copy. Unit
tests lock validate-before-commit on `processing/` and the ETag sidecar.
They do not stand up `ProfileProcessor` + Room just to watch the copy: native
validate writes `processing/config.yaml` before parse, so a failing refresh
is expected to dirty that file. The committed snapshot stays imported because
the copy is after `fetchProfile` returns.

## Do not

- Reject a parseable profile because `x-getline-*` **headers** are missing or
  unexpected.
- Treat request `User-Agent` or response `X-GetLine-Profile` as a security gate
  or as a switch for YAML body policy.
- Treat absent `x-getline-profile.android.excluded-packages` as an error.
- Send package names into Mihomo rules, or let a subscription own `tun`,
  inbounds, or `VpnService.Builder.allowBypass()`.
- Fold user-editable proxy-groups or their metadata into this contract.
