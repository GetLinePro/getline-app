# Mihomo (clash-foss) product patches

Parent-tracked patches applied on top of the `core/src/foss/golang/clash`
git submodule (`MetaCubeX/mihomo`).

| Patch | Purpose |
| --- | --- |
| `0001-disable-ssh-outbound-no_ssh.patch` | Security gate: build tag `no_ssh` + stub; unlink product SSH outbound |

## Why not a submodule commit?

GetLine does not publish a long-lived fork of Mihomo for every product gate.
The parent gitlink stays on upstream SHA; product deltas live here so a clean
checkout + `scripts/apply-mihomo-patches.sh` matches CI and release builds.

## Apply

```bash
# after submodule init/update
./scripts/apply-mihomo-patches.sh
```

Gradle `core` Golang builds depend on the same script (idempotent).

CI: run after `git submodule update --init --recursive --force`.

**Not** the same as `.github/patch/*.patch` (those patch **GOROOT** only).

## Refresh after Mihomo bump

1. `git submodule update --remote` (or pin new SHA in parent).
2. Re-apply; if reject, refresh the patch against the new tree.
3. Re-verify: `go list -tags 'foss,with_gvisor,cmfa,no_ssh' -deps` has no `metacubex/ssh`.
