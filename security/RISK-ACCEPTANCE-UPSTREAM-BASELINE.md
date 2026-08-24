# Risk acceptance: upstream SCA/SAST baseline

**Status:** accepted for GetLine **spike / pre-release only** (expires at production gate)  
**Date:** 2026-07-27  
**Branch context:** `spike/getline-feasibility`  
**Owner:** GetLine maintainers  
**Not a production certification.** This document must not be treated as a
standing exemption after the spike.

## Scope

This acceptance covers **known findings captured in**:

- `security/baselines/osv-baseline.json` (SCA / OSV-Scanner)
- `security/baselines/semgrep-baseline.json` (SAST / Semgrep)

It does **not** accept arbitrary future findings. Scripts report **NEW vs baseline**;
only the current baseline set is accepted here.

### Production gate (required before release freeze)

Replace the bulk “android-core accepted under the same baseline” line with the
per-advisory applicability table in:

- **`security/ANDROID-CORE-SCA-APPLICABILITY.md`** (axes: package reachability,
  vulnerable operation, product exposure, final status; residual R1–R3)
- source scores (generated): `security/reports/osv-android-core.md`

Do **not** collapse statuses into a single bare `not_affected` / `affected: 0`.
Use the vocabulary in that file (`not_affected_by_reported_package`,
`conditionally_not_reachable`, `confirmed_affected_default_product_path`, …).

SSH outbound is tracked as **three separate layers** (see applicability doc):

| Layer | State |
| --- | --- |
| **Decision** | Not a planned GetLine product path (`type: ssh` / VPN-over-plain-SSH). |
| **Required enforcement** | **Done via parent-tracked patch** `core/patches/mihomo/0001-…` + `no_ssh` tag (not a published submodule SHA). |
| **Current state** | After `apply-mihomo-patches.sh` (CI/Gradle/README): `type: ssh` rejected; real adapter not in product `libclash`. Clean gitlink alone is insufficient. |

Residual after enforcement: R3 artifact check is done (`scripts/check-libclash-artifact-gate.sh`
on the just-built `libclash.so`); re-open fork parity / host-key trust only if SSH
is re-enabled. Keep patch applying on every clean submodule update.
CVE-2025-22870 is closed because the shipped `.so` resolves `golang.org/x/net`
to ≥ `v0.36.0` (currently `v0.55.0`), not because environment proxy is unused.

Without that table (or an updated successor), this risk acceptance is **void for
production/Play**.

## Why accept now

1. **Upstream inheritance.** The vulnerable Go graph is largely CMFA/Mihomo
   (`core/src/foss/golang`, vendored `clash`/mihomo) plus the thin GetLine JNI
   bridge (`core/src/main/golang/native`). Product work is currently focused on
   Android UX and RWP auth, not a full core dependency rebases.
2. **No clean call-graph signal yet.** OSV call analysis does not load this
   Android-oriented Go tree in CI (`govulncheck` fails on native DNS symbols).
   Reachability is therefore mostly **`unknown`** for shipped modules, not
   proven-exploitable.
3. **Non-shipped noise separated.** Findings only in
   `core/src/foss/golang/clash/test/go.mod` are treated as **unreachable** for
   product risk (tests are not packaged into `libclash.so`).
4. **Controls outside the vulns list.** Release builds remain non-debuggable for
   production intent, geodata is pinned/checksummed, and native auth tokens are
   handled with explicit client constraints. That does not remove CVEs, but
   bounds exposure while upstream is tracked.

## Snapshot (local baseline generation)

Approximate picture from the first aggregated Android-core report
(re-run `./scripts/security/run-sca.sh` for current numbers):

- Raw OSV rows (all modules): ~485
- Aggregated root causes (package + advisory): ~144 full tree / ~93 android_core
- Scored android_core advisories (non-UNKNOWN severity): ~20
- Packages involved in android_core: mainly `golang.org/x/crypto`, `x/net`,
  `x/oauth2`, `x/sys`, `x/text`, Go `stdlib`
- Reachability: shipped = mostly **unknown** (no call graph); test lockfile =
  **unreachable**

## What is accepted

| Class | Acceptance |
| --- | --- |
| SCA findings listed in `osv-baseline.json` | Accepted as **known upstream risk** until dependency upgrade or replacement |
| SAST findings in `semgrep-baseline.json` (strcpy in JNI bridge, `unsafe` in native Go, debug pprof/http in native debug helpers) | Accepted as **known native/upstream patterns** for this spike; not expanded without review |
| Android-core subset of SCA | **Spike-only** bulk accept; track via `osv-android-core.md` + **`ANDROID-CORE-SCA-APPLICABILITY.md`**. SSH enforcement landed (`no_ssh`); R3 artifact gate landed (`scripts/check-libclash-artifact-gate.sh`). CVE-2025-22870 closed by shipped `x/net` ≥0.36.0 (currently 0.55.0 on `libclash.so`). |

## What is **not** accepted

- **New** SCA/SAST findings after baseline (reported as `NEW vs baseline`)
- Secrets or hard-coded credentials (Semgrep `p/secrets` — none in baseline)
- Skipping scanners entirely
- Claiming CVEs are unreachable without call-graph or equivalent evidence

## Android-core boundary

Shipped Go inputs for Android core (see `core/build.gradle.kts`):

- `core/src/foss/golang/go.mod` (flavor Go source set)
- `core/src/main/golang/go.mod` (`cfa` module / replace target)
- `core/src/foss/golang/clash/go.mod` (mihomo via replace)

Not product-shipped:

- `core/src/foss/golang/clash/test/go.mod`

Gradle/Android Java-Kotlin transitive dependencies are **out of scope** of this
baseline until a lockfile/SBOM path exists.

## Review triggers (re-open acceptance)

Revisit this document when any of the following happens:

- Mihomo / Go toolchain / `golang.org/x/*` upgrades
- Enabling working call analysis / govulncheck for the Android Go tree
- Moving toward production/Play release freeze
- A **NEW** finding is severity HIGH/CRITICAL on an `android_core` package
- Public exploit evidence for a baselined advisory in our usage mode

## Residual risk statement

By accepting the upstream baseline, GetLine knowingly ships a client whose
native proxy core may contain unfixed third-party CVEs present in the CMFA /
Mihomo dependency graph. Mitigation is monitoring (non-blocking CI + aggregated
reports), not current remediation. This is acceptable for feasibility/spike and
internal validation; it is **not** a production security certification.

## Evidence commands

```bash
./scripts/security/run-sca.sh
./scripts/security/run-sast.sh
# Review:
#   security/reports/osv-aggregated.md
#   security/reports/osv-android-core.md
#   security/ANDROID-CORE-SCA-APPLICABILITY.md
#   security/reports/semgrep-summary.json
```
