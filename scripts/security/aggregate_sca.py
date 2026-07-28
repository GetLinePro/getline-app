#!/usr/bin/env python3
"""Aggregate OSV-Scanner JSON into root-cause rows and Android-core views.

Input: OSV scan JSON (results[].packages[].groups + vulnerabilities).
Output:
  - aggregated findings by (module, package, advisory group)
  - reachability classification (reachable | unreachable | unknown)
  - android-core subset based on known shipped go.mod paths
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

# go.mod paths that feed the Android libclash.so / core build.
# See core/build.gradle.kts: foss/golang is the Go source set; main/golang is
# the cfa module + JNI native bridge; clash is mihomo via replace.
ANDROID_CORE_LOCKFILES = {
    "core/src/foss/golang/go.mod",
    "core/src/main/golang/go.mod",
    "core/src/foss/golang/clash/go.mod",
}

# Present in the tree but not linked into the Android product binary.
NON_SHIPPED_LOCKFILES = {
    "core/src/foss/golang/clash/test/go.mod",
}


def normalize_path(path: str) -> str:
    path = path.replace("\\", "/")
    if path.startswith("/src/"):
        path = path[len("/src/") :]
    return path.lstrip("./")


def module_label(path: str) -> str:
    path = normalize_path(path)
    mapping = {
        "core/src/foss/golang/go.mod": "android-core:foss",
        "core/src/main/golang/go.mod": "android-core:cfa",
        "core/src/foss/golang/clash/go.mod": "android-core:mihomo",
        "core/src/foss/golang/clash/test/go.mod": "non-shipped:mihomo-tests",
    }
    return mapping.get(path, f"other:{path}")


def ship_scope(path: str) -> str:
    path = normalize_path(path)
    if path in ANDROID_CORE_LOCKFILES:
        return "android_core"
    if path in NON_SHIPPED_LOCKFILES:
        return "non_shipped"
    return "unknown_module"


def primary_advisory(aliases: list[str], ids: list[str]) -> str:
    pool = list(aliases or []) + list(ids or [])
    for prefix in ("CVE-", "GHSA-", "GO-"):
        for item in pool:
            if item.startswith(prefix):
                return item
    return pool[0] if pool else "UNKNOWN"


_TEXT_SEVERITY_RANK = {
    "CRITICAL": 9.5,
    "HIGH": 7.5,
    "MODERATE": 5.5,
    "MEDIUM": 5.5,
    "LOW": 2.0,
    "UNKNOWN": -1.0,
    "NONE": -1.0,
}


def severity_rank(score: str) -> float:
    """Order severities for sorting. Accepts CVSS numeric strings or text levels."""
    if score is None:
        return -1.0
    text = str(score).strip()
    if not text:
        return -1.0
    upper = text.upper()
    if upper in _TEXT_SEVERITY_RANK:
        return _TEXT_SEVERITY_RANK[upper]
    try:
        return float(text)
    except ValueError:
        return -1.0


def severity_bucket(score: str) -> str:
    """Normalize CVSS numbers and OSV text levels into CRITICAL/HIGH/MEDIUM/LOW."""
    if score is None:
        return "UNKNOWN"
    text = str(score).strip()
    if not text:
        return "UNKNOWN"
    upper = text.upper()
    if upper in ("CRITICAL", "HIGH", "LOW"):
        return upper
    if upper in ("MODERATE", "MEDIUM"):
        return "MEDIUM"
    if upper in ("UNKNOWN", "NONE"):
        return "UNKNOWN"
    try:
        value = float(text)
    except ValueError:
        return "UNKNOWN"
    if value >= 9.0:
        return "CRITICAL"
    if value >= 7.0:
        return "HIGH"
    if value >= 4.0:
        return "MEDIUM"
    if value > 0:
        return "LOW"
    return "UNKNOWN"


def classify_reachability(group: dict[str, Any], path: str) -> str:
    """Map OSV call-analysis signals + ship scope to a coarse reachability label.

    OSV may expose experimental call analysis under various keys depending on
    version. Without those signals, shipped modules are `unknown` and
    non-shipped modules are treated as `unreachable` for product risk.
    """
    scope = ship_scope(path)
    if scope == "non_shipped":
        return "unreachable"

    # Common / experimental fields observed across osv-scanner versions.
    for key in (
        "experimental_analysis",
        "experimentalAnalysis",
        "called",
        "call_analysis",
    ):
        value = group.get(key)
        if value is None:
            continue
        if isinstance(value, bool):
            return "reachable" if value else "unreachable"
        if isinstance(value, dict):
            # map[id] -> {called: bool} style
            called_flags = []
            for item in value.values():
                if isinstance(item, dict) and "called" in item:
                    called_flags.append(bool(item["called"]))
                elif isinstance(item, bool):
                    called_flags.append(item)
            if called_flags:
                return "reachable" if any(called_flags) else "unreachable"

    return "unknown"


def extract_rows(raw: dict[str, Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for result in raw.get("results", []):
        path = normalize_path((result.get("source") or {}).get("path") or "")
        for package in result.get("packages", []):
            meta = package.get("package") or {}
            pkg_name = meta.get("name") or ""
            pkg_version = meta.get("version") or ""
            ecosystem = meta.get("ecosystem") or ""
            vulns_by_id = {
                v.get("id"): v for v in package.get("vulnerabilities") or [] if v.get("id")
            }
            groups = package.get("groups") or []
            if not groups:
                # Fallback: one synthetic group per vulnerability.
                for vuln_id, vuln in vulns_by_id.items():
                    groups.append(
                        {
                            "ids": [vuln_id],
                            "aliases": vuln.get("aliases") or [],
                            "max_severity": "",
                        }
                    )

            for group in groups:
                ids = list(group.get("ids") or [])
                aliases = list(group.get("aliases") or [])
                # Prefer aliases from grouped vulns if group aliases empty.
                if not aliases:
                    for vuln_id in ids:
                        aliases.extend((vulns_by_id.get(vuln_id) or {}).get("aliases") or [])
                # de-dupe aliases preserving order
                seen_alias: set[str] = set()
                aliases = [a for a in aliases if not (a in seen_alias or seen_alias.add(a))]

                advisory = primary_advisory(aliases, ids)
                max_sev = str(group.get("max_severity") or "").strip()
                # Prefer numeric/group score; else OSV text levels on any group id
                # (GHSA entries often carry CRITICAL/HIGH/MODERATE while GO-* does not).
                if not max_sev:
                    for vuln_id in list(ids) + list(aliases):
                        vuln = vulns_by_id.get(vuln_id) or {}
                        text = (vuln.get("database_specific") or {}).get("severity")
                        if text:
                            max_sev = str(text).strip()
                            break
                if not max_sev:
                    pool = set(ids) | set(aliases)
                    for vuln in vulns_by_id.values():
                        vuln_pool = set(vuln.get("aliases") or []) | {
                            vuln.get("id") or ""
                        }
                        if not (vuln_pool & pool):
                            continue
                        text = (vuln.get("database_specific") or {}).get("severity")
                        if text:
                            max_sev = str(text).strip()
                            break

                summary = ""
                for vuln_id in ids:
                    details = vulns_by_id.get(vuln_id) or {}
                    summary = details.get("summary") or details.get("details") or ""
                    if summary:
                        summary = re.sub(r"\s+", " ", summary).strip()[:180]
                        break

                rows.append(
                    {
                        "module": module_label(path),
                        "module_path": path,
                        "ship_scope": ship_scope(path),
                        "ecosystem": ecosystem,
                        "package": pkg_name,
                        "version": pkg_version,
                        "advisory": advisory,
                        "aliases": aliases,
                        "ids": ids,
                        "max_severity": max_sev,
                        "severity_bucket": severity_bucket(max_sev),
                        "reachability": classify_reachability(group, path),
                        "summary": summary,
                    }
                )
    return rows


def _merge_row(item: dict[str, Any], row: dict[str, Any]) -> None:
    item["occurrence_count"] = item.get("occurrence_count", 1) + 1
    for alias in row["aliases"]:
        if alias not in item["aliases"]:
            item["aliases"].append(alias)
    for vuln_id in row["ids"]:
        if vuln_id not in item["ids"]:
            item["ids"].append(vuln_id)
    if severity_rank(row["max_severity"]) > severity_rank(item["max_severity"]):
        item["max_severity"] = row["max_severity"]
        item["severity_bucket"] = row["severity_bucket"]
    rank = {"reachable": 2, "unknown": 1, "unreachable": 0}
    if rank.get(row["reachability"], 0) > rank.get(item["reachability"], 0):
        item["reachability"] = row["reachability"]
    if not item.get("summary") and row.get("summary"):
        item["summary"] = row["summary"]
    modules = item.setdefault("modules", [])
    if row["module"] not in modules:
        modules.append(row["module"])
    scopes = item.setdefault("ship_scopes", [])
    if row["ship_scope"] not in scopes:
        scopes.append(row["ship_scope"])
    # Prefer android_core when any occurrence is shipped.
    if "android_core" in scopes:
        item["ship_scope"] = "android_core"
    elif "non_shipped" in scopes and "unknown_module" not in scopes:
        item["ship_scope"] = "non_shipped"


def aggregate_by_module(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse into module + package + advisory roots."""
    buckets: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    for row in rows:
        key = (row["module"], row["package"], row["advisory"], row["version"])
        item = buckets.get(key)
        if item is None:
            buckets[key] = {
                "module": row["module"],
                "modules": [row["module"]],
                "module_path": row["module_path"],
                "ship_scope": row["ship_scope"],
                "ship_scopes": [row["ship_scope"]],
                "ecosystem": row["ecosystem"],
                "package": row["package"],
                "version": row["version"],
                "advisory": row["advisory"],
                "aliases": list(row["aliases"]),
                "ids": list(row["ids"]),
                "max_severity": row["max_severity"],
                "severity_bucket": row["severity_bucket"],
                "reachability": row["reachability"],
                "summary": row["summary"],
                "occurrence_count": 1,
            }
            continue
        _merge_row(item, row)

    result = list(buckets.values())
    result.sort(
        key=lambda r: (
            0 if r["ship_scope"] == "android_core" else 1,
            -severity_rank(r["max_severity"]),
            r["package"],
            r["advisory"],
            r["module"],
        )
    )
    return result


def aggregate_root_causes(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse cross-module duplicates: package + version + advisory.

    This is the human-facing root-cause list (typically tens of rows, not hundreds).
    """
    buckets: dict[tuple[str, str, str], dict[str, Any]] = {}
    for row in rows:
        key = (row["package"], row["version"], row["advisory"])
        item = buckets.get(key)
        if item is None:
            buckets[key] = {
                "module": row["module"],
                "modules": [row["module"]],
                "module_path": row["module_path"],
                "ship_scope": row["ship_scope"],
                "ship_scopes": [row["ship_scope"]],
                "ecosystem": row["ecosystem"],
                "package": row["package"],
                "version": row["version"],
                "advisory": row["advisory"],
                "aliases": list(row["aliases"]),
                "ids": list(row["ids"]),
                "max_severity": row["max_severity"],
                "severity_bucket": row["severity_bucket"],
                "reachability": row["reachability"],
                "summary": row["summary"],
                "occurrence_count": 1,
            }
            continue
        _merge_row(item, row)

    result = list(buckets.values())
    result.sort(
        key=lambda r: (
            0 if r["ship_scope"] == "android_core" else 1,
            -severity_rank(r["max_severity"]),
            r["package"],
            r["advisory"],
        )
    )
    return result


def _table(rows: list[dict[str, Any]]) -> list[str]:
    lines = [
        "| Scope | Sev | Reach | Package | Version | Advisory | Modules |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for row in rows:
        modules = ", ".join(row.get("modules") or [row.get("module", "")])
        lines.append(
            "| {scope} | {sev} | {reach} | `{package}` | `{version}` | `{advisory}` | {modules} |".format(
                scope=row["ship_scope"],
                sev=row["severity_bucket"],
                reach=row["reachability"],
                package=row["package"],
                version=row["version"],
                advisory=row["advisory"],
                modules=modules,
            )
        )
    return lines


def render_markdown(aggregated: list[dict[str, Any]], title: str) -> str:
    scored = [r for r in aggregated if r["severity_bucket"] != "UNKNOWN"]
    unknown = [r for r in aggregated if r["severity_bucket"] == "UNKNOWN"]
    lines = [
        f"# {title}",
        "",
        f"Root causes: **{len(aggregated)}** "
        f"(package + advisory; modules collapsed)",
        f"- with score: **{len(scored)}**",
        f"- severity unknown: **{len(unknown)}**",
        "",
        "## Priority (scored advisories)",
        "",
    ]
    lines.extend(_table(scored) if scored else ["_None_"])
    lines.extend(["", "## Severity unknown", ""])
    lines.extend(_table(unknown) if unknown else ["_None_"])
    lines.append("")
    return "\n".join(lines)


def summarize(aggregated: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "root_causes": len(aggregated),
        "by_ship_scope": dict(Counter(r["ship_scope"] for r in aggregated)),
        "by_reachability": dict(Counter(r["reachability"] for r in aggregated)),
        "by_severity": dict(Counter(r["severity_bucket"] for r in aggregated)),
        "android_core_root_causes": sum(
            1 for r in aggregated if r["ship_scope"] == "android_core"
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", required=True, help="Path to osv-raw.json")
    parser.add_argument("--out-dir", required=True, help="Reports directory")
    parser.add_argument(
        "--prefix",
        default="osv",
        help="Output filename prefix (default: osv)",
    )
    args = parser.parse_args()

    raw_path = Path(args.raw)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    raw = json.loads(raw_path.read_text())

    rows = extract_rows(raw)
    by_module = aggregate_by_module(rows)
    # Primary human view: package + advisory across modules.
    aggregated = aggregate_root_causes(rows)
    android_core = [r for r in aggregated if r["ship_scope"] == "android_core"]
    summary = summarize(aggregated)
    summary["by_module_rows"] = len(by_module)
    summary_android = summarize(android_core)

    prefix = args.prefix
    (out_dir / f"{prefix}-aggregated.json").write_text(
        json.dumps(
            {
                "schema": "getline-osv-aggregated/v1",
                "summary": summary,
                "findings": aggregated,
                "by_module_findings": by_module,
            },
            indent=2,
        )
        + "\n"
    )
    (out_dir / f"{prefix}-android-core.json").write_text(
        json.dumps(
            {
                "schema": "getline-osv-android-core/v1",
                "lockfiles": sorted(ANDROID_CORE_LOCKFILES),
                "summary": summary_android,
                "findings": android_core,
            },
            indent=2,
        )
        + "\n"
    )
    (out_dir / f"{prefix}-aggregated.md").write_text(
        render_markdown(aggregated, "SCA aggregated findings")
    )
    (out_dir / f"{prefix}-android-core.md").write_text(
        render_markdown(android_core, "SCA Android-core findings only")
    )

    print(
        "SCA aggregate: {total} root causes "
        "({android} android_core, {non} non_shipped, {unk} other; "
        "{by_module} module-expanded rows)".format(
            total=summary["root_causes"],
            android=summary["by_ship_scope"].get("android_core", 0),
            non=summary["by_ship_scope"].get("non_shipped", 0),
            unk=summary["by_ship_scope"].get("unknown_module", 0),
            by_module=summary["by_module_rows"],
        )
    )
    print(
        "Reachability: "
        + ", ".join(f"{k}={v}" for k, v in sorted(summary["by_reachability"].items()))
    )
    print(
        "Severity: "
        + ", ".join(f"{k}={v}" for k, v in sorted(summary["by_severity"].items()))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
