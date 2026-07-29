# Agent notes

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
