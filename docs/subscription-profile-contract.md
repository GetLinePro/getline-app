# GetLine subscription profile contract

**Decided:** 2026-08-11, restated 2026-08-13. **Status:** in effect. Issue #26.

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

## Response markers

Expected on the GetLine projection:

```http
Content-Type: text/yaml; charset=utf-8
X-GetLine-Profile: subscription
X-GetLine-Schema: 1
X-GetLine-Tag: …
X-GetLine-Status: …
ETag: W/"…"
Vary: Origin, Accept-Encoding, User-Agent
```

`x-getline-schema: 1` is the current schema. A later number means a capability
this client may not apply. The client does not parse or enforce the number.

`X-GetLine-Tag` and `X-GetLine-Status` are optional user attributes. After a
successful primary-config fetch (`200` or `304`) the client stores them on the
imported profile and builds the Subscription card from that snapshot. A
successful response that omits a header clears the saved value. A failed fetch
leaves the last snapshot. Do not log either value.

The YAML body may also carry an `x-getline-profile` mapping. The client does not
read it. Judge the answering template by the headers.

Do not log `profile-web-page-url`. The panel puts the subscription token in that
URL path.

## Compatibility

- The panel must not send a capability a given client version cannot apply.
  The dangerous case is an unknown proxy type: the core rejects the whole
  configuration, not one node.
- Roll out backend first. A new install has no last-known-good profile; if the
  first import is rejected there is nothing to keep.
- Missing or unexpected markers are a diagnostic signal. A parseable profile is
  still applied. Only unparseable YAML, a non-2xx / transport failure, or a core
  apply failure reject the update.
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

- Reject a parseable profile because `x-getline-*` is missing or unexpected.
- Treat User-Agent as a security gate.
- Fold user-editable proxy-groups or their metadata into this contract.
