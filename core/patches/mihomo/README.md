# Mihomo (clash-foss) product patches

Parent-tracked patches applied on top of the `core/src/foss/golang/clash`
git submodule (`MetaCubeX/mihomo`).

| Patch | Purpose |
| --- | --- |
| `0001-disable-ssh-outbound-no_ssh.patch` | Security gate: build tag `no_ssh` + stub; unlink product SSH outbound |
| `0002-restrict-subscription-redirects.patch` | Subscription/HTTP fetch: same-host redirects only; reject HTTPS→HTTP; trailing-dot host canonicalize + tests |
| `0003-discard-logrus-output-under-cmfa.patch` | GL-04: under `cmfa`, logrus `Out` → `io.Discard` via `productLogOutput()` build tags; event bus kept for in-app log |
| `0004-close-tun-fd-before-tunnew.patch` | VpnService fd: close on `sing_tun.New` error before `tunNew`; after `tunNew`, `Listener.Close` owns it |

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

CI: run after `git submodule update --init --recursive --force`, then
`./scripts/verify-mihomo-gate.sh` (after Go is on PATH).

**Not** the same as `.github/patch/*.patch` (those patch **GOROOT** only).

## Verify (security gate)

`git apply` succeeding is not enough. Upstream can refactor so the patch still
applies while `no_ssh` no longer excludes SSH. Always run:

```bash
./scripts/verify-mihomo-gate.sh
```

Checks:

1. Submodule HEAD matches the parent-recorded gitlink.
2. Working tree is exactly the patch result (tracked + untracked sets and bytes).
3. `GOOS=android GOARCH=arm64 go list -tags 'foss,with_gvisor,cmfa,no_ssh' -deps`
   has no `metacubex/ssh`, and the same without `no_ssh` still has it
   (control — gate not vacuous). Target is the Android product graph, not
   the CI host OS (`VERIFY_GOOS` / `VERIFY_GOARCH` override defaults).

Requires `go` in PATH; missing Go is a hard failure (no silent skip).

## Refresh after Mihomo bump

Reproducible procedure:

```bash
# 1. Move submodule to the new upstream pin
git submodule update --remote --force

# 2. Apply product patches (fails if the patch no longer fits → refresh patch)
./scripts/apply-mihomo-patches.sh

# 3. Verify the security gate still works (fails if no_ssh is vacuous)
#    --skip-gitlink: parent HEAD still has the old pin until you commit the gitlink
./scripts/verify-mihomo-gate.sh --skip-gitlink

# 4. Build
./gradlew :app:assembleAlphaProdDebug

# 5. Device smoke: connect, traffic, server selection

# 6. Record the new gitlink SHA in the parent and commit (with refreshed
#    patches if any). After commit, plain verify without --skip-gitlink must pass:
./scripts/verify-mihomo-gate.sh
```

### When the patch does not apply

1. Do **not** commit ad-hoc edits inside the submodule as the product source of truth.
2. Reset the submodule to the intended upstream SHA (clean tree).
3. Refresh `core/patches/mihomo/*.patch` against that tree (edit the patch file
   in the parent repo).
4. Re-run apply + verify until both succeed.
5. Commit only parent-tracked paths: the updated `.patch`, any README/script
   changes, and the new gitlink.

`m core/src/foss/golang/clash` after a successful apply is expected local dirt
from the patches (tracked mods + untracked new files). That is the product
working tree, not an accidental fork commit.

Expected footprint after all product patches:

| Kind | Paths |
| --- | --- |
| dirty tracked | `adapter/outbound/ssh.go`, `constant/features/tags.go`, `component/http/http.go`, `log/log.go`, `listener/sing_tun/server.go` |
| untracked | `adapter/outbound/ssh_stub.go`, `ssh_stub_test.go`, `constant/features/no_ssh.go`, `no_ssh_stub.go`, `component/http/http_redirect_test.go`, `log/output_cmfa.go`, `log/output_default.go`, `log/output_cmfa_test.go` |

Do **not** commit those paths inside the Mihomo submodule. Refresh
`core/patches/mihomo/*.patch` in the parent repo instead.
