#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: GitHub CLI (gh) is required for the release preflight check." >&2
    exit 1
fi
if ! gh act --version >/dev/null 2>&1; then
    echo "ERROR: gh-act is not installed." >&2
    echo "Install it with:" >&2
    echo "  gh extension install https://github.com/nektos/gh-act" >&2
    exit 1
fi

run_dry_run() {
    local payload="$1"
    local output

    if ! output="$(
        gh act workflow_dispatch \
            -W .github/workflows/build-release.yaml \
            -j BuildRelease \
            -e "$payload" \
            --secret GITHUB_TOKEN= \
            --strict \
            --dryrun 2>&1
    )"; then
        printf '%s\n' "$output" >&2
        return 1
    fi
    printf '%s' "$output"
}

echo "act preflight: validating release workflow..."
normal_output="$(run_dry_run tools/act/workflow-dispatch-main.json)"
aab_output="$(run_dry_run tools/act/workflow-dispatch-main-aab.json)"

aab_step='Run Main Release build — signed meta AAB'
if grep -Fq "$aab_step" <<<"$normal_output"; then
    echo "ERROR: normal release payload unexpectedly enables the AAB step." >&2
    exit 1
fi
if ! grep -Fq "$aab_step" <<<"$aab_output"; then
    echo "ERROR: AAB release payload does not enable the AAB step." >&2
    exit 1
fi

dangerous_steps=(
    'Free runner disk space'
    'Tag must not exist yet'
    'Upload signed artifacts to the run'
    'Publish release commit and tag'
    'Upload Release'
)

assert_dangerous_steps_absent() {
    local scenario="$1"
    local output="$2"
    local step

    for step in "${dangerous_steps[@]}"; do
        if grep -Fq "$step" <<<"$output"; then
            echo "ERROR: $scenario ACT plan includes dangerous step: $step" >&2
            exit 1
        fi
    done
}

assert_dangerous_steps_absent "normal release" "$normal_output"
assert_dangerous_steps_absent "AAB release" "$aab_output"

python3 - <<'PY'
import json
from pathlib import Path

for path in Path("tools/act").glob("*.json"):
    json.loads(path.read_text())
PY

bash -n tools/act/create-test-signing.sh
bash -n tools/act/validate-release-preflight.sh

echo "act preflight: validation passed (normal=APK, aab=APK+AAB)."
