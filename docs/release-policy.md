# Release policy

Two numbers with two different jobs. Confusing them is what produced a `0.2.0`
for two bug fixes and, separately, an install that could not be upgraded.

| Field | Means | Source |
|---|---|---|
| `versionName` | the product release, SemVer | bump chosen by a human, applied by the release workflow |
| `versionCode` | which published release this is | previous published code **+ 1** |

Both live in `build.gradle.kts` and are written there by
`.github/workflows/build-release.yaml`, which commits them and tags that commit.

## Why versionCode is not a build counter

`fdroid/README.md` requires that F-Droid can rebuild `metaProdRelease` from a
public tag and compare artifacts, with `SOURCE_DATE_EPOCH` taken from the tagged
commit. Every manifest field, `versionCode` included, must therefore follow from
the source under the tag.

So `GITHUB_RUN_NUMBER` is unusable (not derivable from source, and it resets if a
workflow file is renamed), and a commit count is unusable too (it does not change
when the same commit is rebuilt, and it depends on history not being rewritten).

Rebuilding a tag *should* yield the same `versionCode` — it is the same release.
If you need a new installable build, make a new release.

**One-time floor: 2001.** An accidental `0.2.0` build with code `2000` reached a
device before this policy existed, and Android refuses an update with a lower
code. The workflow clamps the next code to at least 2001.

## Bump levels while 0.x

The type is decided by the **result for the user**, not by how much new code was
written. Moving the OAuth callback to another host added a trampoline, an edge
rewrite and a new host — and it is still a `fix`, because the user gets a login
that works, not a new capability.

| Commits in the PR | Minimum release label |
|---|---|
| `BREAKING CHANGE` / `type!:` | `release:minor` (see below) |
| `feat` | `release:minor` |
| `fix`, `perf` | `release:patch` |
| `refactor`, `build`, `revert` | `release:patch` |
| `chore`, `docs`, `test`, `ci` | `release:none` |

While the major version is 0, a breaking change is a **minor** bump. The
`0.x → 1.0.0` step is refused by the release workflow: going 1.0 is a deliberate
act, done by editing `build.gradle.kts` in a reviewed PR.

## Where the intent comes from

Exactly one `release:patch|minor|major|none` label per pull request. The label is
the human intent; Conventional Commit types are a **cross-check**, and a
disagreement fails the PR (`pr-hygiene.yaml`). Neither is trusted alone: commit
messages in this repository are frequently written by an LLM, and the type does
drift — commit `2cc362a3` was authored as `feat(auth)` for what was a `fix`, and
an unattended semver would have produced exactly the rejected `0.2.0`.

Releases do aggregate several PRs — #9 and #10 landed before the first release
run — so the PR label alone is not enough: it only describes its own PR. The
release workflow therefore re-derives the required minimum from every commit
since the version line last changed, and **refuses a dispatch input lower than
that**. The human still chooses the bump; they just cannot understate it.

The range starts at the last commit that touched `versionName`, found with
`git log -G`. Not `git describe`: this fork inherits upstream tags, and
`describe` would happily return `v2.11.32`.

A subject in that range that is **not** a Conventional Commit fails the release.
Skipping it would let `Add servers screen` hide a `feat` behind `bump=patch`.
Repository policy is squash-only and the PR gate validates the squash title, so
an unclassifiable subject on main arrived outside the intended path and needs a
human.

One recovery exception is pinned to the `0.8.2` release base: the exact Git
objects for the eight GitHub merge commits from PRs #148-#156 are skipped while
their non-merge commits are still classified. The base guard makes the exception
inert after the next release; new merge commits remain release-blocking.

If releases ever need to be prepared ahead of time, the source of truth moves to
a dedicated release PR and per-PR labels become inputs to it.

## Gates

`pr-hygiene.yaml` (blocking, unlike the advisory `security-baseline.yaml`):

- branch name matches `(feat|fix|chore|ci|docs|refactor|perf|test|build|revert)/kebab-case`;
- the branch contains current `main` (GitHub's native "up to date" requirement is
  a paid branch-protection feature for private repos, so it is done in CI);
- no keystore / `signing.properties` tracked in git;
- no `pull_request`, `pull_request_target` or `workflow_call` workflow uses the
  `secrets` context or delegates with `secrets: inherit` — same-repo pull requests
  do receive secrets, only forks are restricted, so this boundary needs a gate
  rather than a convention;
- every external action in the release path is pinned to a full commit SHA;
- release signing files are created only under `$RUNNER_TEMP`, removed by an
  `always()` step and gone before the next external action;
- `versionName` / `versionCode` are not hand-edited;
- exactly one `release:*` label, consistent with the commit types **and the PR
  title** — with squash merges the title is the only subject that reaches main.

Alongside `pr-hygiene`, every pull request runs `build-alpha-unsigned.yaml`:
unit tests first (`app:testAlphaProdReleaseUnitTest`), then the native build.
Tests run **only** there — the release job builds and lints but does not test.
That is safe only because merges are squash-only and nothing reaches `main`
without passing this gate; if a direct push to `main` ever becomes possible,
the release job needs its own test step.

Dependency-update pull requests opened by Dependabot cannot pass these gates at
all: the branch name (`dependabot/go_modules/...`) fails the naming rule, the
branch is not kept current with `main`, and no `release:*` label is applied.
Take the bump onto a conforming branch and close the bot's pull request.

**Documented exception:** PR #9 (`0.1.3`, code `1003`) carried a hand-edited
version, merged before these gates existed.

## ABI matrix

The published APK carries `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Each
one is a separate native build of the mihomo core, and they dominate the build:
measured on the same tree, the full matrix spends 16m48s in the Gradle build
step against 6m05s for `arm64-v8a` alone. So they are not all built on every
check.

- **Pull request:** primary ABI (`arm64-v8a`) plus unit tests. CI runs no
  emulator and holds no instrumented tests, so the other three verify nothing a
  pull request consumes — only that the core still compiles for them.
  `armeabi-v7a` is the one worth naming: it is the only 32-bit target in the
  matrix, and 32-bit assumptions are where Go code actually breaks.
- **ABI-sensitive change** — mihomo submodule bump, cgo, build tags, NDK or
  CMake configuration, anything under `core/src/main/golang`: full matrix. This
  is escalated by the author, by running `build-alpha-unsigned.yaml` manually
  (`workflow_dispatch` builds all four). Deliberately a human decision: a
  path-based rule cannot tell an architecture-independent edit to Go code from
  one that changes the compiled result, and would escalate on every Go commit.
- **Release:** full matrix, always. `build-release.yaml` never passes
  `-Pgetline.abis`, so it builds the default list.

Narrowing is available to any build through `-Pgetline.abis=arm64-v8a`
(`build.gradle.kts`). An unknown value fails configuration rather than quietly
producing an APK without a native core.

Consequence, accepted deliberately: a compile failure specific to any ABI but
`arm64-v8a` — 32-bit `armeabi-v7a` included — surfaces in the manual release run
rather than on the pull request. `build-release.yaml` and `build-pre-release.yaml`
are `workflow_dispatch`, so the full-matrix gate is a button someone presses
before publishing, not an automatic barrier on `main`. A release run is the
wrong place to discover a broken build, so escalate on the pull request whenever
the change touches the native core.

Versions do not have to be published contiguously. `0.1.3` exists only as a local
build; the first artifact of this pipeline is `0.1.4` (code 2001). That is not a
claim that a CI change earned a patch bump — it is the first properly signed
release of everything accumulated since.

## Nothing is published before the artifacts exist

Order inside the release job — every irreversible step is last:

```
compute bump → verify it covers accumulated commits → tag is free
→ write version, commit and tag LOCALLY
→ restore keystore → certificate must match EXPECTED_SIGNING_CERT_SHA256
→ build APK + AAB → verify signatures, artifact certificate and hashes
→ remove keystore and signing properties
→ confirm main has not moved → git push --atomic commit + tag
→ publish the GitHub release
```

A wrong secret, a bad base64 blob, a Gradle failure or a failed signature check
therefore leaves **no** published release commit and **no** tag. A failure in
GitHub Release creation happens after the atomic push and leaves the release
commit and tag published without release assets; that state requires explicit
operator recovery. `SOURCE_DATE_EPOCH` does not need the push: the local release
commit already exists when it is read.

Every `0.x` release is published as a **pre-release**, so none of them becomes
"Latest release". The flag is derived from the version and switches itself off at
`1.0.0`. Do not create releases or tags by hand: the tag must sit on the release
commit that carries the matching version, or the F-Droid rebuild has nothing
consistent to compare, and the workflow will refuse to reuse an occupied tag.

Signing material is checked in three cheap steps before the ~13-minute build,
because Gradle only reports a wrong key when it signs: the keystore must decode,
`SIGNING_KEY_ALIAS` must name an entry in it, and that selected entry's
certificate must match the expected fingerprint. The alias check exists because
a secret pasted with trailing whitespace failed the first release run after 13
minutes.

`EXPECTED_SIGNING_CERT_SHA256` is a repository **variable**, not a secret — the
fingerprint is published in Digital Asset Links. It is checked twice: against the
restored keystore before building, and against the signed APK afterwards. An
empty variable fails the run rather than passing silently. Update it, the deployed
`assetlinks.json` and the three keystore secrets together whenever the release key
changes.

GitHub Actions secrets are a delivery path into CI, not a recoverable backup.
Until signing ownership is formally transferred, the repository owner must keep one
encrypted backup blob in two off-machine locations and its passphrase
separately. Losing the key makes existing non-Play installs non-upgradable: the
current users must reinstall and lose local app data, then the replacement key,
the three signing secrets, `EXPECTED_SIGNING_CERT_SHA256` and the deployed
`assetlinks.json` must change together. At the first Play release, enroll the
existing signing key in Play App Signing and create a separate upload key; a
separate APK channel still needs either a recoverable signing key or APKs signed
and downloaded from Play.

## Release run

`Build Release` is `workflow_dispatch` only, refuses any ref but `main`, and
serializes on `concurrency: getline-release`. It computes the next version,
checks the tag is free, commits and creates the tag locally (never moves an
existing one — F-Droid resolves tag → source), builds and verifies artifacts,
then pushes the commit and tag atomically:

- signed alpha APKs (`assembleAlphaProdRelease`) — what testers install;
- signed meta AAB (`bundleMetaProdRelease`) — **only when `build_aab=true`**.

The AAB is off by default because nothing consumes it yet: the app is not on
Play, F-Droid rebuilds `metaProdRelease` from the tag itself, and
`build-pre-release.yaml` lints and bundles the Play shape (unsigned) on demand —
it is `workflow_dispatch` only, so run it by hand before a release or after a
change to Play-shaped packaging. Building the AAB in the release job as well
doubled the peak disk
usage and ran a standard hosted runner out of space:
`R8: java.io.IOException: No space left on device`. Turn it on for an actual Play
upload; the job then reports the AAB hash in the audit table.

A standard runner has ~14 GB free on `/`, which this project (Go core, NDK, R8,
ABI splits) does not fit next to the preinstalled toolchains, so the job first
removes dotnet, GHC, boost, the CodeQL cache and the docker images, and prints
`df` around each build. Explicit `rm` rather than a third-party disk-cleanup
action. External actions run only before the key is restored or after the
`always()` teardown has removed both signing files. All external actions in this
path are pinned to full commit SHAs.
Larger runners are a separate paid class — a GitHub Pro subscription does not
change this 14 GB.

Two separate Gradle invocations: `build.gradle.kts` turns ABI splits off when a
bundle task is in the same invocation, so combining them would quietly ship a
single universal APK.

For a safe local preflight with a disposable signing key, see
[`docs/act-release-preflight.md`](act-release-preflight.md).

Current pull-request builds stay **unsigned** and do not reference release
signing secrets.

### Private repository limitation

The repository is private on a plan that does not provide approval-gated
environments or native branch protection. Signing secrets therefore cannot be
held behind a required environment reviewer today. The compensating controls
are a `workflow_dispatch`-only release job that refuses non-`main` refs, no
`secrets` context or `secrets: inherit` in PR-triggered/reusable workflows,
full-SHA action pins, signing files outside the workspace and teardown before
publication.

This does not eliminate the GL-06 insider/account-compromise case: a malicious
same-repository pull request can add a secret reference and run before the
post-factum hygiene gate reports the violation. Maintainers must treat workflow
changes as security-sensitive and must not merge them without review. If the
repository becomes public or moves to a plan with protected environments, move
the signing secrets into a release environment with required reviewers; that is
the preventative control, and it replaces this documented residual acceptance.

Signing teardown is deliberately fail-closed: if either temporary file cannot be
removed, publication is cancelled even after a successful build. Because signed
artifacts are not uploaded before teardown, that run must be rebuilt.

The job summary records `versionName`, `versionCode`, tag, commit SHA, the
signing certificate SHA-256 and artifact SHA-256. The certificate fingerprint
must match the deployed `.well-known/assetlinks.json`, or Auth Tab sign-in fails
with `RESULT_VERIFICATION_FAILED` — that is a real incident, not a hypothetical
(2026-07-30, after the release keystore was replaced).
