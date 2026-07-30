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
Merges are squash-only (enforced in repository settings) and the PR gate validates
the squash title, so an unclassifiable subject on main can only have arrived by a
direct push or a local merge — which is exactly the case that needs a human.

If releases ever need to be prepared ahead of time, the source of truth moves to
a dedicated release PR and per-PR labels become inputs to it.

## Gates

`pr-hygiene.yaml` (blocking, unlike the advisory `security-baseline.yaml`):

- branch name matches `(feat|fix|chore|ci|docs|refactor|perf|test|build|revert)/kebab-case`;
- the branch contains current `main` (GitHub's native "up to date" requirement is
  a paid branch-protection feature for private repos, so it is done in CI);
- no keystore / `signing.properties` tracked in git;
- no `pull_request`-triggered workflow references `SIGNING_*` — same-repo pull
  requests do receive secrets, only forks are restricted, so this boundary needs
  a gate rather than a convention;
- `versionName` / `versionCode` are not hand-edited;
- exactly one `release:*` label, consistent with the commit types **and the PR
  title** — with squash merges the title is the only subject that reaches main.

**Documented exception:** PR #9 (`0.1.3`, code `1003`) carried a hand-edited
version, merged before these gates existed.

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
→ upload artifacts to the run
→ confirm main has not moved → git push --atomic commit + tag
→ publish the GitHub release
```

A wrong secret, a bad base64 blob, a Gradle failure or a failed upload therefore
leaves **no** release commit and **no** tag — only a failed run with a
downloadable build that claims nothing. `SOURCE_DATE_EPOCH` does not need the
push: the local release commit already exists when it is read.

Every `0.x` release is published as a **pre-release**, so none of them becomes
"Latest release". The flag is derived from the version and switches itself off at
`1.0.0`. Do not create releases or tags by hand: the tag must sit on the release
commit that carries the matching version, or the F-Droid rebuild has nothing
consistent to compare, and the workflow will refuse to reuse an occupied tag.

Signing material is checked in three cheap steps before the ~13-minute build,
because Gradle only reports a wrong key when it signs: the keystore must decode,
its certificate must match the expected fingerprint, and `SIGNING_KEY_ALIAS` must
name an entry in it. The alias check exists because a secret pasted with trailing
whitespace failed the first release run after 13 minutes.

`EXPECTED_SIGNING_CERT_SHA256` is a repository **variable**, not a secret — the
fingerprint is published in Digital Asset Links. It is checked twice: against the
restored keystore before building, and against the signed APK afterwards. An
empty variable fails the run rather than passing silently. Update it, the deployed
`assetlinks.json` and the three keystore secrets together whenever the release key
changes.

## Release run

`Build Release` is `workflow_dispatch` only, refuses any ref but `main`, and
serializes on `concurrency: getline-release`. It computes the next version,
checks the tag is free, commits, creates the tag (never moves an existing one —
F-Droid resolves tag → source), pushes commit and tag atomically, then builds
from that state:

- signed alpha APKs (`assembleAlphaProdRelease`) — what testers install;
- signed meta AAB (`bundleMetaProdRelease`) — **only when `build_aab=true`**.

The AAB is off by default because nothing consumes it yet: the app is not on
Play, F-Droid rebuilds `metaProdRelease` from the tag itself, and
`build-pre-release.yaml` already lints and bundles the Play shape (unsigned) on
every push to `main`. Building it in the release job as well doubled the peak disk
usage and ran a standard hosted runner out of space:
`R8: java.io.IOException: No space left on device`. Turn it on for an actual Play
upload; the job then reports the AAB hash in the audit table.

A standard runner has ~14 GB free on `/`, which this project (Go core, NDK, R8,
ABI splits) does not fit next to the preinstalled toolchains, so the job first
removes dotnet, GHC, boost, the CodeQL cache and the docker images, and prints
`df` around each build. Explicit `rm` rather than a third-party disk-cleanup
action: nothing else gets to execute inside the job that holds the signing key.
Larger runners are a separate paid class — a GitHub Pro subscription does not
change this 14 GB.

Two separate Gradle invocations: `build.gradle.kts` turns ABI splits off when a
bundle task is in the same invocation, so combining them would quietly ship a
single universal APK.

Pull-request builds stay **unsigned**. The release key is never available to a
workflow a pull request can trigger.

The job summary records `versionName`, `versionCode`, tag, commit SHA, the
signing certificate SHA-256 and artifact SHA-256. The certificate fingerprint
must match the deployed `.well-known/assetlinks.json`, or Auth Tab sign-in fails
with `RESULT_VERIFICATION_FAILED` — that is a real incident, not a hypothetical
(2026-07-30, after the release keystore was replaced).
