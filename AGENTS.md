# Agent notes

## Versions and releases

Do **not** hand-edit `versionName` / `versionCode` in `build.gradle.kts` — a PR
gate rejects it. Both are written by the `Build Release` workflow, which commits
and tags them. Every PR needs exactly one `release:patch|minor|major|none` label,
and it must agree with the Conventional Commit types in the PR.

The bump follows the **result for the user**, not the amount of new code: a repair
of a promised flow is `fix` even when it adds new files.

Full rules, including why `versionCode` is not a build counter (F-Droid
reproducibility): `docs/release-policy.md`.

## Browser auth — read before touching it

Auth spans the app, a Caddy edge we control, and a proprietary backend (RWP) we do
not. Half the constraints are not visible in the code.

- contract, host layout, curl checks, **what is done and what is open**:
  `docs/spikes/android-auth/README.md`
- edge config as deployed: `private deployment configuration`
- decisions, incident history, rejected options:
  `docs/internal/android-auth-journal.md` — `docs/internal/` is git-ignored, so this
  one exists only in a local checkout

Three facts that are cheap to get wrong:

1. **Completion is not on the portal host.** Prod callback is `auth.getline.pro`,
   because `app.getline.pro` ships a PWA with `"scope": "/"` whose WebAPK becomes a
   verified handler for the whole domain and steals the redirect.
2. **RWP still redirects to the portal host.** A Caddy rewrite, gated on a marker
   cookie set by the trampolines, moves that one hop. Do not "simplify" it away, and
   do not plan work that depends on the vendor changing an endpoint — that path is
   not ours to schedule.
3. **`assetlinks.json` is one shared file** (`EDGE_WEBROOT`) served by every
   host. Editing it "for prod" also edits stage.

Auth changes need both flavors green:
`./gradlew :app:testAlphaProdDebugUnitTest :app:testAlphaE2eDebugUnitTest`.

## Mihomo (clash-foss) product changes

Do **not** treat local edits under `core/src/foss/golang/clash` as the source of truth.
That path is a git submodule (`MetaCubeX/mihomo`); product deltas live in the parent repo:

- patches: `core/patches/mihomo/*.patch`
- apply: `./scripts/apply-mihomo-patches.sh`
- verify (CI + local gate): `./scripts/verify-mihomo-gate.sh`

After a clean submodule checkout, apply patches, then run the verifier.
A forced submodule update that conflicts with a patch must fail closed — refresh
the parent-tracked patch against the new upstream SHA; do not commit ad-hoc
forks inside the submodule.

`.gitmodules` uses `ignore = dirty` so normal `git status` hides applied-patch
working-tree noise. A **changed gitlink SHA** still shows. Do not use
`ignore = all`. Exact-tree safety is `./scripts/verify-mihomo-gate.sh`, already
in CI.
