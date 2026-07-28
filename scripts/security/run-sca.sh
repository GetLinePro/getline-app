#!/usr/bin/env bash
# SCA via OSV-Scanner (non-blocking by default).
#
# Produces:
#   security/reports/osv-raw.json              — full recursive raw report
#   security/reports/osv-android-core-raw.json — shipped Android core lockfiles only
#   security/reports/osv-aggregated.{json,md}
#   security/reports/osv-android-core.{json,md}
#   security/reports/osv-summary.json          — baseline delta
#
# Exit codes:
#   0 — completed (findings may exist; non-blocking)
#   2 — tool/setup failure
#
# Env:
#   SECURITY_BLOCKING=1  — exit 1 when NEW findings vs baseline appear
#   SECURITY_REPORT_DIR  — where to write reports (default: security/reports)
set -euo pipefail

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_docker

RAW_JSON="$REPORT_DIR/osv-raw.json"
ANDROID_CORE_RAW_JSON="$REPORT_DIR/osv-android-core-raw.json"
SUMMARY_JSON="$REPORT_DIR/osv-summary.json"
BASELINE="$BASELINE_DIR/osv-baseline.json"
AGGREGATE_PY="$REPO_ROOT/scripts/security/aggregate_sca.py"

# Lockfiles linked into libclash.so / Android core (see core/build.gradle.kts).
ANDROID_CORE_LOCKFILES=(
  "core/src/foss/golang/go.mod"
  "core/src/main/golang/go.mod"
  "core/src/foss/golang/clash/go.mod"
)

echo "==> SCA: OSV-Scanner full tree ($OSV_IMAGE)"
# Skip call analysis: native Android Go packages do not load cleanly in the
# scanner image, so reachability stays unknown unless signals appear later.
set +e
docker run --rm \
  -v "$REPO_ROOT:/src" \
  -w /src \
  "$OSV_IMAGE" \
  scan source -r /src \
  --no-call-analysis=all \
  --format json \
  --output /src/security/reports/osv-raw.json \
  --verbosity error
FULL_EC=$?
set -e

if [[ ! -f "$RAW_JSON" ]]; then
  echo "OSV full scan produced no output file" >&2
  exit 2
fi

echo "==> SCA: OSV-Scanner Android-core lockfiles only"
LOCKFILE_ARGS=()
for lockfile in "${ANDROID_CORE_LOCKFILES[@]}"; do
  LOCKFILE_ARGS+=(--lockfile "/src/$lockfile")
done

set +e
docker run --rm \
  -v "$REPO_ROOT:/src" \
  -w /src \
  "$OSV_IMAGE" \
  scan source \
  --no-call-analysis=all \
  --format json \
  --output /src/security/reports/osv-android-core-raw.json \
  --verbosity error \
  "${LOCKFILE_ARGS[@]}"
CORE_EC=$?
set -e

if [[ ! -f "$ANDROID_CORE_RAW_JSON" ]]; then
  echo "OSV android-core scan produced no output file" >&2
  exit 2
fi

echo "==> SCA: aggregate full + android-core views"
python3 "$AGGREGATE_PY" --raw "$RAW_JSON" --out-dir "$REPORT_DIR" --prefix osv
python3 "$AGGREGATE_PY" \
  --raw "$ANDROID_CORE_RAW_JSON" \
  --out-dir "$REPORT_DIR" \
  --prefix osv-core-scan

python3 - "$RAW_JSON" "$BASELINE" "$SUMMARY_JSON" "$REPORT_DIR" <<'PY'
import json, sys
from pathlib import Path

raw_path, baseline_path, summary_path, report_dir = sys.argv[1:5]
raw = json.loads(Path(raw_path).read_text())
baseline_ids = set()
if Path(baseline_path).is_file():
    base = json.loads(Path(baseline_path).read_text())
    for f in base.get("findings", []):
        baseline_ids.add((
            f.get("id"),
            f.get("ecosystem"),
            f.get("package"),
            f.get("version"),
            f.get("path"),
        ))

current = []
seen = set()
for r in raw.get("results", []):
    path = (r.get("source") or {}).get("path") or ""
    path = path.replace("/src/", "").lstrip("/")
    for pkg in r.get("packages", []):
        pi = pkg.get("package") or {}
        for v in pkg.get("vulnerabilities", []):
            key = (
                v.get("id"),
                pi.get("ecosystem"),
                pi.get("name"),
                pi.get("version"),
                path,
            )
            if key in seen:
                continue
            seen.add(key)
            current.append({
                "id": key[0],
                "ecosystem": key[1],
                "package": key[2],
                "version": key[3],
                "path": key[4],
            })

new = [f for f in current if (
    f["id"], f["ecosystem"], f["package"], f["version"], f["path"]
) not in baseline_ids]

current_keys = {(
    f["id"], f["ecosystem"], f["package"], f["version"], f["path"]
) for f in current}
resolved = [
    {
        "id": k[0],
        "ecosystem": k[1],
        "package": k[2],
        "version": k[3],
        "path": k[4],
    }
    for k in sorted(baseline_ids - current_keys)
]

agg_path = Path(report_dir) / "osv-aggregated.json"
core_path = Path(report_dir) / "osv-android-core.json"
aggregated = json.loads(agg_path.read_text()) if agg_path.is_file() else {}
android_core = json.loads(core_path.read_text()) if core_path.is_file() else {}

summary = {
    "tool": "osv-scanner",
    "total_raw_findings": len(current),
    "baseline": len(baseline_ids),
    "new": len(new),
    "resolved": len(resolved),
    "new_findings": new[:100],
    "resolved_findings": resolved[:100],
    "aggregated": aggregated.get("summary"),
    "android_core": android_core.get("summary"),
}
Path(summary_path).write_text(json.dumps(summary, indent=2) + "\n")

print(f"SCA raw findings: {summary['total_raw_findings']}")
print(f"SCA baseline size:  {summary['baseline']}")
print(f"SCA NEW vs baseline: {summary['new']}")
print(f"SCA resolved since baseline: {summary['resolved']}")
if summary.get("aggregated"):
    print(f"SCA aggregated root causes: {summary['aggregated'].get('root_causes')}")
if summary.get("android_core"):
    print(
        "SCA android-core root causes: "
        f"{summary['android_core'].get('root_causes')}"
    )
if new:
    print("New SCA findings (up to 20):")
    for f in new[:20]:
        print(f"  - {f['id']}  {f['ecosystem']}:{f['package']}@{f['version']}  ({f['path']})")
PY

echo "Reports under $REPORT_DIR:"
echo "  osv-raw.json"
echo "  osv-android-core-raw.json"
echo "  osv-aggregated.json / .md"
echo "  osv-android-core.json / .md"
echo "  osv-core-scan-aggregated.json / .md  (from android-core-only scan)"
echo "  osv-summary.json"

NEW_COUNT="$(python3 -c 'import json; print(json.load(open("'"$SUMMARY_JSON"'"))["new"])')"
if [[ "${SECURITY_BLOCKING:-0}" == "1" && "$NEW_COUNT" != "0" ]]; then
  echo "SECURITY_BLOCKING=1 and $NEW_COUNT new SCA findings" >&2
  exit 1
fi

# Non-blocking: scanner may exit 1 when vulns exist.
exit 0
