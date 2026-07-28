# Security scans (baseline, non-blocking)

| Layer | Tool | Why |
| --- | --- | --- |
| **SCA** | [OSV-Scanner](https://github.com/google/osv-scanner) | Free, OSV DB, good Go/`go.mod` coverage; no account required |
| **SAST** | [Semgrep](https://semgrep.dev/) | Free community rules for Kotlin/Java/Go/C/secrets; fast local runs |

Both are **non-blocking**: they always exit `0` unless `SECURITY_BLOCKING=1` and **new** findings appear vs baseline.

## Local

Requires Docker.

```bash
./scripts/security/run-sca.sh
./scripts/security/run-sast.sh
```

Reports: `security/reports/` (gitignored). Raw JSON is always written there and
uploaded as CI artifacts.

### SCA outputs

| File | Meaning |
| --- | --- |
| `osv-raw.json` | Full recursive OSV raw report |
| `osv-android-core-raw.json` | OSV scan limited to shipped Android-core lockfiles |
| `osv-aggregated.md` / `.json` | Root causes: module + package + advisory |
| `osv-android-core.md` / `.json` | Same aggregation filtered to `android_core` ship scope |

Committed (not under `reports/`):

| File | Meaning |
| --- | --- |
| `ANDROID-CORE-SCA-APPLICABILITY.md` | Per-advisory **product** applicability (reachability × exposure); production gate — not a scanner fix |
| `RISK-ACCEPTANCE-UPSTREAM-BASELINE.md` | Spike bulk acceptance + gate requirements |
| `osv-summary.json` | Baseline delta + aggregate counts |

SSH outbound disable is a **parent-tracked Mihomo patch** (`core/patches/mihomo/`), not a submodule gitlink advance. Clean builds must run `./scripts/apply-mihomo-patches.sh` after `git submodule update` (CI and Gradle do this).

Reachability labels:

- `reachable` — call analysis said used (rare today; analysis disabled/broken for this tree)
- `unreachable` — non-shipped module (e.g. mihomo tests) or call analysis said unused
- `unknown` — shipped module without call-graph evidence

## Baseline

Known findings live in:

- `security/baselines/osv-baseline.json`
- `security/baselines/semgrep-baseline.json`

Risk acceptance for that known set:

- `security/RISK-ACCEPTANCE-UPSTREAM-BASELINE.md`

After reviewing new findings and accepting them:

```bash
./scripts/security/update-baselines.sh
# then update the risk-acceptance date/notes if the accepted set grew
```

## CI

Workflow `.github/workflows/security-baseline.yaml` runs both scans with
`continue-on-error: true` and uploads **raw + aggregated** reports as artifacts.

## Scope limits (honest)

- **SCA today:** Go modules under `core/` (`go.mod`). There is no Gradle lockfile, so Android/Java transitive dependencies are **not** fully covered yet.
- **Android-core SCA:** `foss/golang`, `main/golang`, and `clash` lockfiles only (not `clash/test`).
- **SAST:** app/design/service Kotlin + main native Go/C paths. Vendored clash tree under `core/src/foss/golang/clash/**` is excluded for noise/time.
- Deprecation warnings for `EncryptedSharedPreferences` are build warnings, not SAST findings.

## Optional blocking mode

```bash
SECURITY_BLOCKING=1 ./scripts/security/run-sca.sh
SECURITY_BLOCKING=1 ./scripts/security/run-sast.sh
```

Fails only on findings **not** in the baseline.
