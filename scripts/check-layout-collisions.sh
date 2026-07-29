#!/usr/bin/env bash
# Fail if :design and :getlineui ship the same layout resource name.
# Duplicate databinding layouts collapse to one R.layout id after merge and
# can ClassCastException at inflate (see Bug 4 / dialog_text_field).
#
# Usage (repo root):
#   ./scripts/check-layout-collisions.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

design_dir="design/src/main/res/layout"
getlineui_dir="getlineui/src/main/res/layout"

if [[ ! -d "$design_dir" ]]; then
  echo "error: missing $design_dir" >&2
  exit 1
fi
if [[ ! -d "$getlineui_dir" ]]; then
  echo "error: missing $getlineui_dir" >&2
  exit 1
fi

mapfile -t collisions < <(
  comm -12 \
    <(find "$design_dir" -maxdepth 1 -type f -name '*.xml' -printf '%f\n' | sort) \
    <(find "$getlineui_dir" -maxdepth 1 -type f -name '*.xml' -printf '%f\n' | sort)
)

if ((${#collisions[@]} == 0)); then
  echo "OK   no layout name collisions between :design and :getlineui"
  exit 0
fi

echo "error: layout name collision(s) between :design and :getlineui:" >&2
for f in "${collisions[@]}"; do
  echo "  - $f" >&2
done
echo "Rename the :getlineui layout (prefer get_line_ prefix) and update inflate sites." >&2
exit 1
