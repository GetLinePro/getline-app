#!/usr/bin/env bash
# Re-run scanners and rewrite security/baselines/* from current findings.
# Use after reviewing NEW findings and accepting them into the known set.
set -euo pipefail

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

"$REPO_ROOT/scripts/security/run-sca.sh"
"$REPO_ROOT/scripts/security/run-sast.sh"

python3 - "$REPORT_DIR" "$BASELINE_DIR" <<'PY'
import datetime, hashlib, json, sys
from pathlib import Path

report_dir, baseline_dir = map(Path, sys.argv[1:3])
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

# OSV
raw = json.loads((report_dir / "osv-raw.json").read_text())
findings = []
seen = set()
for r in raw.get("results", []):
    path = ((r.get("source") or {}).get("path") or "").replace("/src/", "").lstrip("/")
    for pkg in r.get("packages", []):
        pi = pkg.get("package") or {}
        for v in pkg.get("vulnerabilities", []):
            key = (v.get("id"), pi.get("ecosystem"), pi.get("name"), pi.get("version"), path)
            if key in seen:
                continue
            seen.add(key)
            findings.append({
                "id": key[0],
                "ecosystem": key[1],
                "package": key[2],
                "version": key[3],
                "path": key[4],
            })
findings.sort(key=lambda x: (x["id"] or "", x["package"] or "", x["version"] or "", x["path"] or ""))
(baseline_dir / "osv-baseline.json").write_text(json.dumps({
    "schema": "getline-osv-baseline/v1",
    "generated_at": now,
    "tool": "osv-scanner:v2.2.3",
    "scope": "Go modules under core/ (Gradle/Android lockfile not present; not covered)",
    "count": len(findings),
    "findings": findings,
}, indent=2) + "\n")
print(f"Updated OSV baseline: {len(findings)} findings")

# Semgrep
raw = json.loads((report_dir / "semgrep-raw.json").read_text())
sg = []
for r in raw.get("results", []):
    path = (r.get("path") or "").replace("/src/", "").lstrip("/")
    start = (r.get("start") or {}).get("line")
    end = (r.get("end") or {}).get("line")
    check = r.get("check_id")
    key = f"{check}|{path}|{start}|{end}"
    sg.append({
        "key": key,
        "fingerprint": hashlib.sha256(key.encode()).hexdigest()[:16],
        "check_id": check,
        "path": path,
        "start_line": start,
        "end_line": end,
        "severity": (r.get("extra") or {}).get("severity"),
        "message": ((r.get("extra") or {}).get("message") or "")[:200],
    })
sg.sort(key=lambda x: x["key"])
(baseline_dir / "semgrep-baseline.json").write_text(json.dumps({
    "schema": "getline-semgrep-baseline/v1",
    "generated_at": now,
    "tool": "semgrep:1.128.1",
    "configs": ["p/kotlin", "p/java", "p/security-audit", "p/secrets"],
    "count": len(sg),
    "findings": sg,
}, indent=2) + "\n")
print(f"Updated Semgrep baseline: {len(sg)} findings")
PY

echo "Baselines written under $BASELINE_DIR"
