# Android-core Priority (20) — applicability (not scanner fix)

**Date:** 2026-07-27  
**Source:** `security/reports/osv-android-core.md` scored table (generated; gitignored)  
**Scope:** GetLine Android client / shipped Go core (`libclash` graph)  

This document is the production-gate per-advisory table required by
`security/RISK-ACCEPTANCE-UPSTREAM-BASELINE.md`. It is **not** a claim that
fork code is free of equivalent bugs, and **not** final closure of residual items.

## Product decision vs enforcement vs current state

These three are **not** the same.

| Layer | State (2026-07-27) |
| --- | --- |
| **Decision** | SSH outbound (`type: ssh` / “VPN over plain SSH”) is **not** a planned GetLine product path. |
| **Required enforcement** | **Implemented via parent-tracked patch** (not a published Mihomo fork SHA): `core/patches/mihomo/0001-disable-ssh-outbound-no_ssh.patch` applied by `scripts/apply-mihomo-patches.sh` after submodule checkout; Android Go tags include `no_ssh` (`core/build.gradle.kts`). Stub rejects `NewSsh`; `github.com/metacubex/ssh` is **not** linked under product tags. |
| **Current state** | Clean checkout gitlink stays upstream `e26714a1` (or current pin). **Without** applying the patch, `no_ssh` is an unused tag and SSH remains linked — CI/Gradle/README must run the apply script. After apply: `type: ssh` fails at parse; real `ssh.go` is not compiled into product `libclash`. |

Exposure labels below use **decision** for product intent and **current state** for residual binary/config surface.

---

## Method (two axes)

A single “status” is easy to misread. Rows use:

| Column | Values | Meaning |
| --- | --- | --- |
| **Package reachability** | `absent` / `linked` / `fork-lineage` | Is the OSV-reported package path (or a known export/fork of the same code) in the Android-tagged dep graph? |
| **Vulnerable operation** | `client` / `server` / `agent` / `jws` / `html` / `httpproxy` / … | What the advisory actually requires |
| **Product exposure** | `default` / `optional` / `unavailable` | GetLine **intent** vs residual surface: see decision table above |
| **Final status** | see vocabulary below | Gate label for auditors |

### Package reachability notes

- **`absent`:** `go list -tags foss,with_gvisor,cmfa,no_ssh -deps` does not include the reported import path (e.g. `golang.org/x/crypto/ssh`, `github.com/metacubex/ssh`).
- **`linked`:** that import path appears in the same dep graph.
- **`fork-lineage`:** reported path is absent, but product **links** `github.com/metacubex/ssh` (documented export of `x/crypto/ssh`). After `no_ssh` enforcement, product builds do **not** link the fork; residual fork-lineage applies only if SSH is re-enabled without the tag.

Build tags match `core/build.gradle.kts`: `foss`, `with_gvisor`, `cmfa`, `no_ssh`.

### Final status vocabulary

| Status | Use when |
| --- | --- |
| `not_affected_by_reported_package` | Reported package path not shipped; **does not** alone prove historical fork parity |
| `fork_lineage_reviewed_separately` | Residual **only if** same-lineage code is linked; closed for product builds under `no_ssh` |
| `not_affected_by_shipped_operation` | Path may exist, but vulnerable operation (server/agent/jws/html) is not in product graph / not used |
| `conditionally_not_reachable` | Was: only reachable via optional SSH config before enforcement. **Superseded for product builds** by hard-disable (`no_ssh`) |
| `enforcement_disabled_surface` | Config/binary path hard-disabled for product (build tag + stub) |
| `needs_investigation` | Residual still open for gate (non-SSH or unresolved) |
| `confirmed_affected_default_product_path` | Proven on default GetLine path without optional features |
| `fixed_upstream_in_current_tree` | Current tree versions include the fix |

**Do not** read a lone `not_affected` as “fork is clean forever.” Re-open if `no_ssh` is removed.

---

## Evidence summary

| Item | Result |
| --- | --- |
| Reported `golang.org/x/crypto/ssh*` | **absent** under product tags |
| `github.com/metacubex/ssh` (+ internals) | **absent** under product tags (`no_ssh`); may still appear in `go.mod` require list for upstream mergeability |
| `metacubex/ssh/agent`, `…/knownhosts` | **not** in dep list for clash root |
| Product SSH code | `adapter/outbound/ssh.go` **not compiled** (`!no_ssh`); `ssh_stub.go` rejects `NewSsh` |
| `golang.org/x/oauth2/jws` | **absent** (linked: `oauth2`, `clientcredentials`, `internal` via Tailscale oauthkey) |
| `golang.org/x/net/html` | **absent** |
| `golang.org/x/net/http/httpproxy` | **linked** via `github.com/metacubex/http` |
| Planned product use of SSH outbound | **No** (decision); **enforced** via `no_ssh` |

---

## Priority (20) table

| Advisory | Sev | Package | Package reachability | Vulnerable operation | Product exposure | Final status |
| --- | --- | --- | --- | --- | --- | --- |
| CVE-2026-46595 | CRITICAL | x/crypto@0.33 | absent | server (`VerifiedPublicKeyCallback`) | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39830 | CRITICAL | x/crypto@0.33 | absent | server (deadlock) | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39831 | CRITICAL | x/crypto@0.33 | absent | client FIDO/U2F | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39832 | CRITICAL | x/crypto@0.33 | absent | agent | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39833 | CRITICAL | x/crypto@0.33 | absent | agent | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39834 | CRITICAL | x/crypto@0.33 | absent | client channel I/O | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2026-42508 | CRITICAL | x/crypto@0.33 | absent | knownhosts `@revoked` | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` — **not** the same as host-key MITM (see Residual #2) |
| CVE-2025-22869 | HIGH | x/crypto@0.33 | absent | server (slow KEX / pending content) | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-39829 | HIGH | x/crypto@0.33 | absent | client parse / crypto params | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2026-46597 | HIGH | x/crypto@0.33 | absent | client panic (underflow) | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2025-22868 | HIGH | oauth2@0.24 | jws **absent** | jws token parse | unavailable | `not_affected_by_shipped_operation` |
| CVE-2026-39827 | MEDIUM | x/crypto@0.33 | absent | channel reject / leak | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2026-25680 | MEDIUM | x/net (was @0.35) | html **absent** | HTML parse DoS | unavailable | `not_affected_by_shipped_operation`; product graph now `x/net@0.55.0` |
| CVE-2026-39828 | MEDIUM | x/crypto@0.33 | absent | SSH cert restriction bypass | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2025-47914 | MEDIUM | x/crypto@0.33 | absent | agent | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2025-58181 | MEDIUM | x/crypto@0.33 | absent | unbounded memory (ssh) | unavailable | `not_affected_by_reported_package`; `enforcement_disabled_surface` |
| CVE-2026-39835 | MEDIUM | x/crypto@0.33 | absent | server panic (CheckHostKey/Authenticate) | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2026-46598 | MEDIUM | x/crypto@0.33 | absent | agent client panic | unavailable | `not_affected_by_reported_package`; `not_affected_by_shipped_operation` |
| CVE-2025-22872 | MEDIUM | x/net (was @0.35) | html **absent** | HTML tokenizer / DOM | unavailable | `not_affected_by_shipped_operation`; product graph now `x/net@0.55.0` |
| CVE-2025-22870 | MEDIUM | x/net (was @0.35) | httpproxy **linked** | NO_PROXY / IPv6 zone matching | optional (env proxy; not core VPN UX) | `fixed_upstream_in_current_tree` — product modules pin `golang.org/x/net v0.55.0` (≥0.36.0) |

---

## SSH set: historical “4–6” (pre-enforcement residual)

**Counts on package-path applicability and fork-lineage exposure are not mutually exclusive** as axes. Before `no_ssh`, the same advisory could be both `not_affected_by_reported_package` and fork-lineage residual.

### Exactly four — client-relevant when SSH outbound was linkable

| Advisory | Why client-relevant |
| --- | --- |
| CVE-2026-39834 | Large channel writes / infinite loop — client channel path |
| CVE-2026-39829 | Pathological RSA/DSA params — client parse of peer material |
| CVE-2026-46597 | Byte underflow → panic — client on hostile peer input |
| CVE-2025-58181 | Unbounded memory in ssh — client can be victim of malicious server |

### Two more — interpretation-dependent

| Advisory | Why interpretation-dependent |
| --- | --- |
| CVE-2026-39827 | Often server-framed channel-reject leak; client channel lifecycle not proven clean without broader reading |
| CVE-2026-39828 | SSH cert restriction bypass; outbound used password/key, not cert-restriction flows |

**After enforcement (`no_ssh`):** product builds do not link `metacubex/ssh` and reject `type: ssh`. The 4/6 set is **closed for product exposure** as `enforcement_disabled_surface`. Re-open if SSH is product-supported again — re-check **against `metacubex/ssh`**, not only OSV’s `x/crypto@0.33.0`.

---

## Counts (gate summary)

**Counts are not necessarily mutually exclusive** because package-path applicability and (historical) fork-lineage exposure are **separate axes**.

| Label | Count | Notes |
| --- | --- | --- |
| `not_affected_by_shipped_package_or_operation` / `enforcement_disabled_surface` | **19** | Includes former SSH residual rows now hard-disabled |
| SSH residual still open in product build | **0** | Enforcement landed; re-open if `no_ssh` removed |
| Historical 4 exact / 6 conservative | archived above | Pre-enforcement fork-lineage set |
| `needs_investigation` (non-SSH) | **0** | CVE-2025-22870 closed by `x/net` bump |
| `confirmed_affected_default_product_path` | **0** | Do not shorten to bare `affected: 0` |
| `fixed_upstream_in_current_tree` | **1** | CVE-2025-22870 (`x/net` ≥0.36.0; product pin 0.55.0) |

---

## Residual product risks (independent of OSV rows)

### R1 — Fork patch parity (closed for product binary; re-open if SSH re-enabled)

Under product tags, `github.com/metacubex/ssh` is **not linked**. Upstream source and `go.mod` require may remain for mergeability.

**Decision:** not a planned product path.  
**Enforcement:** done (`no_ssh` + stub).  
**If re-enabled:** establish patch parity of `metacubex/ssh` vs fixed `x/crypto/ssh` before shipping.

### R2 — Default host-key verification (closed for product; re-open if SSH re-enabled)

Historical design risk in `ssh.go` (default `InsecureIgnoreHostKey()` unless fingerprints set). Not compiled under `no_ssh`.

If SSH is product-supported later: require fingerprints / known_hosts and fail closed.

### R3 — Artifact vs `go list`

Applicability above is from module graph + tags, not from `nm`/`libclash.so`
manifest. Production gate should confirm the shipped artifact matches (no
`metacubex/ssh` symbols when built with product tags).

---

## Spike vs production

| Stage | Stance |
| --- | --- |
| **Spike** | Bulk acceptance of baselined SCA remains acceptable; this table replaces informal “all android-core OK”. |
| **Play / production** | Gate base: SSH enforcement done; CVE-2025-22870 fixed by `x/net` pin. Remaining open item: R3 artifact check. |

### Minimum production gate

1. **Decision (done):** SSH outbound is not a planned product path.  
2. **Enforcement (done):** `no_ssh` build tag on Android flavors; stub rejects config; fork not linked under product tags.  
3. **If SSH re-enabled:** patch-parity review of the historical 4 (+ optional 2) against **`metacubex/ssh`**, plus host-key trust model (R2).  
4. Confirm analysis against final **`libclash.so` / build manifest** (R3).  
5. **CVE-2025-22870 (done):** product modules `core/src/main/golang` and `core/src/foss/golang` pin `golang.org/x/net v0.55.0` (fix ≥0.36.0). Verified: `go list -tags foss,with_gvisor,cmfa,no_ssh -deps` resolves `golang.org/x/net/http/httpproxy` to `v0.55.0`. The mihomo submodule `clash/go.mod` may still *declare* `v0.35.0` for upstream mergeability; MVS from the product modules selects `0.55.0`. Do not treat the submodule floor alone as the shipped version.

**Most important remaining work is not another OSV run for SSH** — confirm R3 on the shipped `.so`.

---

## Core update note (Mihomo)

SSH gate is **not** advanced via submodule gitlink. Parent records:

- gitlink → upstream MetaCubeX/mihomo SHA (unchanged by this gate)
- product delta → `core/patches/mihomo/0001-disable-ssh-outbound-no_ssh.patch`
- apply → `./scripts/apply-mihomo-patches.sh` (CI + Gradle `applyMihomoPatches`)

Patch content follows the existing `no_tailscale` pattern (`//go:build !no_ssh` + stub + features). Do **not** delete SSH from upstream `go.mod` solely for this gate.

On Mihomo bump: update gitlink, re-apply patch (refresh if reject), confirm product tags still include `no_ssh`, re-run dep check.

---

## Commands (reproducible)

```bash
# Clean checkout simulation: submodule pin + parent patch
git submodule update --init --recursive --force
./scripts/apply-mihomo-patches.sh

# Product-tagged dep presence (expect no metacubex/ssh)
(cd core/src/foss/golang/clash && \
  go list -tags 'foss,with_gvisor,cmfa,no_ssh' -deps -f '{{.ImportPath}}' . | \
  grep -E 'x/crypto/ssh|metacubex/ssh|x/oauth2|x/net/html|httpproxy')

# Stub rejects NewSsh
(cd core/src/foss/golang/clash && \
  go test -tags 'foss,with_gvisor,cmfa,no_ssh' ./adapter/outbound/ -run TestNewSshDisabled -count=1)

# SCA priority table (writes gitignored security/reports/)
./scripts/security/run-sca.sh
# review generated: security/reports/osv-android-core.md
# review gate doc:  security/ANDROID-CORE-SCA-APPLICABILITY.md
```
