# Local release preflight with `act`

This is a local pipeline check, not a release and not a replacement for the
GitHub-hosted runner gate. It exercises the real release Gradle tasks with a
disposable signing key.

The workflow recognizes the non-empty `ACT` environment set by `act` and skips
everything external or state-changing:

- hosted-runner cleanup, including `docker system prune`;
- the remote tag availability check;
- editing, committing and tagging the checkout;
- artifact upload, the atomic push and GitHub Release creation.

The version rewrite is checked against a temporary copy. Local artifacts embed
the currently committed version, while the audit reports the computed next
version separately.

## Setup

`act` uses Docker; it is a CLI, not a separate Docker service. Install either
the binary or the GitHub CLI extension:

```bash
gh extension install https://github.com/nektos/gh-act
```

The tracked `.actrc` maps `ubuntu-24.04` to the full runner image. It is large,
but includes the Android SDK/NDK missing from the medium image:

```bash
docker pull ghcr.io/catthehacker/ubuntu:full-24.04
tools/act/create-test-signing.sh
```

The generator creates `.act-release.keystore`, `.act.secrets` and `.act.vars`.
They are ignored by Git and contain only disposable local signing material.
Never use the production release key with `act`.

## Fast validation

Run the strict plan checks before a full preflight:

```bash
tools/act/validate-release-preflight.sh
```

This validates both event payloads, checks APK/AAB routing and asserts that the
ACT plan contains none of the cleanup, upload, push or release steps. It does
not start a runner container or run Gradle.

## Full preflight

Normal release shape, producing signed alpha APKs:

```bash
gh act workflow_dispatch \
  -W .github/workflows/build-release.yaml \
  -j BuildRelease \
  -e tools/act/workflow-dispatch-main.json \
  --secret GITHUB_TOKEN= \
  --secret-file .act.secrets \
  --var-file .act.vars
```

Worst-case Play shape, producing APKs and a signed AAB:

```bash
gh act workflow_dispatch \
  -W .github/workflows/build-release.yaml \
  -j BuildRelease \
  -e tools/act/workflow-dispatch-main-aab.json \
  --secret GITHUB_TOKEN= \
  --secret-file .act.secrets \
  --var-file .act.vars
```

The empty `GITHUB_TOKEN` prevents the `gh` extension host from implicitly
forwarding its credential into the workflow. If anonymous API limits prevent
checkout or asset downloads, expose a valid token only to that process:

```bash
GITHUB_TOKEN="$(gh auth token)" gh act workflow_dispatch \
  -W .github/workflows/build-release.yaml \
  -j BuildRelease \
  -e tools/act/workflow-dispatch-main-aab.json \
  --secret GITHUB_TOKEN \
  --secret-file .act.secrets \
  --var-file .act.vars
```

The event files intentionally carry the boolean `build_aab` value. `act` 0.2.89
does not reliably expose `--input build_aab=true` through the typed `inputs`
context. The checked-in payloads also set `GITHUB_REF` to `refs/heads/main`;
the workflow remains fail-closed if that emulation changes.

Do not use `act --bind`: the workflow protects the checkout under `ACT`, but
the default copied workspace remains the safer isolation boundary.

## Initial profiling result

Measured on 2026-07-30 with `act` 0.2.89 and the full image pre-pulled:

- APK-only job: 10.12 GiB peak growth on Docker's filesystem;
- APK + AAB job: 12.38 GiB peak growth.

These local figures exclude the runner image and are capacity estimates, not a
substitute for the real GitHub-hosted release gate.
