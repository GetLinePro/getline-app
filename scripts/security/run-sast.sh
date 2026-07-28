#!/usr/bin/env bash
# SAST via Semgrep (non-blocking by default).
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

RAW_JSON="$REPORT_DIR/semgrep-raw.json"
SUMMARY_JSON="$REPORT_DIR/semgrep-summary.json"
BASELINE="$BASELINE_DIR/semgrep-baseline.json"

echo "==> SAST: Semgrep ($SEMGREP_IMAGE)"
set +e
docker run --rm \
  -v "$REPO_ROOT:/src" \
  -w /src \
  "$SEMGREP_IMAGE" \
  semgrep scan \
  --config p/kotlin \
  --config p/java \
  --config p/security-audit \
  --config p/secrets \
  --exclude '**/build/**' \
  --exclude '**/.gradle/**' \
  --exclude '**/core/src/foss/golang/clash/**' \
  --exclude '**/*.apk' \
  --exclude '**/*.so' \
  --exclude '**/assets/**' \
  --json \
  --output /src/security/reports/semgrep-raw.json \
  /src
SCAN_EC=$?
set -e

if [[ ! -f "$RAW_JSON" ]]; then
  echo "Semgrep scan produced no output file" >&2
  exit 2
fi

python3 - "$RAW_JSON" "$BASELINE" "$SUMMARY_JSON" <<'PY'
import hashlib, json, sys
from pathlib import Path

raw_path, baseline_path, summary_path = sys.argv[1:4]
raw = json.loads(Path(raw_path).read_text())
baseline_keys = set()
if Path(baseline_path).is_file():
    base = json.loads(Path(baseline_path).read_text())
    for f in base.get("findings", []):
        if f.get("key"):
            baseline_keys.add(f["key"])
        else:
            baseline_keys.add(
                f"{f.get('check_id')}|{f.get('path')}|{f.get('start_line')}|{f.get('end_line')}"
            )

current = []
for r in raw.get("results", []):
    path = (r.get("path") or "").replace("/src/", "").lstrip("/")
    start = (r.get("start") or {}).get("line")
    end = (r.get("end") or {}).get("line")
    check = r.get("check_id")
    key = f"{check}|{path}|{start}|{end}"
    current.append({
        "key": key,
        "fingerprint": hashlib.sha256(key.encode()).hexdigest()[:16],
        "check_id": check,
        "path": path,
        "start_line": start,
        "end_line": end,
        "severity": (r.get("extra") or {}).get("severity"),
        "message": ((r.get("extra") or {}).get("message") or "")[:200],
    })

current_keys = {f["key"] for f in current}
new = [f for f in current if f["key"] not in baseline_keys]
resolved = sorted(baseline_keys - current_keys)

summary = {
    "tool": "semgrep",
    "total": len(current),
    "baseline": len(baseline_keys),
    "new": len(new),
    "resolved": len(resolved),
    "new_findings": new,
    "resolved_keys": resolved[:100],
}
Path(summary_path).write_text(json.dumps(summary, indent=2) + "\n")

print(f"SAST total findings: {summary['total']}")
print(f"SAST baseline size:  {summary['baseline']}")
print(f"SAST NEW vs baseline: {summary['new']}")
print(f"SAST resolved since baseline: {summary['resolved']}")
if new:
    print("New SAST findings:")
    for f in new:
        print(f"  - [{f.get('severity')}] {f['check_id']}  {f['path']}:{f['start_line']}")
PY

echo "Reports: $RAW_JSON , $SUMMARY_JSON"

NEW_COUNT="$(python3 -c 'import json; print(json.load(open("'"$SUMMARY_JSON"'"))["new"])')"
if [[ "${SECURITY_BLOCKING:-0}" == "1" && "$NEW_COUNT" != "0" ]]; then
  echo "SECURITY_BLOCKING=1 and $NEW_COUNT new SAST findings" >&2
  exit 1
fi

exit 0
