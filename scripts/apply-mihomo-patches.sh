#!/usr/bin/env bash
# Apply parent-tracked Mihomo (clash-foss) product patches.
# Safe to re-run (idempotent). Required after clean submodule checkout.
#
# Source of truth is core/patches/mihomo/*.patch in the parent repo — not the
# submodule gitlink. A clean `git submodule update --force` alone does NOT
# enable product gates (e.g. no_ssh).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASH="$ROOT/core/src/foss/golang/clash"
PATCH_DIR="$ROOT/core/patches/mihomo"

if [[ ! -d "$CLASH" ]]; then
  echo "error: mihomo submodule missing at $CLASH" >&2
  echo "run: git submodule update --init --recursive" >&2
  exit 1
fi

if [[ ! -d "$PATCH_DIR" ]]; then
  echo "error: patch dir missing: $PATCH_DIR" >&2
  exit 1
fi

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
if ((${#patches[@]} == 0)); then
  echo "no mihomo patches in $PATCH_DIR"
  exit 0
fi

# Paths from 0001-disable-ssh-outbound-no_ssh.patch
SSH_GATE_TRACKED=(
  "adapter/outbound/ssh.go"
  "constant/features/tags.go"
)
SSH_GATE_NEW_FILES=(
  "adapter/outbound/ssh_stub.go"
  "adapter/outbound/ssh_stub_test.go"
  "constant/features/no_ssh.go"
  "constant/features/no_ssh_stub.go"
)

fail_apply() {
  local name="$1"
  shift
  echo "error: cannot apply $name to $CLASH" >&2
  if (($# > 0)); then
    printf '  %s\n' "$@" >&2
  fi
  echo "  expected clean submodule at parent gitlink SHA, then this patch" >&2
  echo "  status:" >&2
  git -C "$CLASH" status --short >&2 || true
  exit 1
}

# True if path has any staged or unstaged change vs HEAD.
path_is_dirty() {
  local rel="$1"
  if ! git -C "$CLASH" diff --quiet HEAD -- "$rel" 2>/dev/null; then
    return 0
  fi
  if ! git -C "$CLASH" diff --quiet --cached -- "$rel" 2>/dev/null; then
    return 0
  fi
  return 1
}

# True if path is tracked by the index.
path_is_tracked() {
  local rel="$1"
  git -C "$CLASH" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1
}

# Materialize post-patch file tree for NEW files into $1 (absolute temp dir).
# Uses HEAD versions of tracked paths + the product patch. No writes under CLASH.
# Note: do not use `git apply --directory` — some git versions reject those paths;
# apply from inside the temp tree instead.
build_expected_ssh_gate_tree() {
  local expect_root="$1"
  local patch="$2"
  local rel
  local patch_abs

  patch_abs="$(cd "$(dirname "$patch")" && pwd)/$(basename "$patch")"

  mkdir -p "$expect_root/adapter/outbound" "$expect_root/constant/features"
  for rel in "${SSH_GATE_TRACKED[@]}"; do
    git -C "$CLASH" show "HEAD:$rel" >"$expect_root/$rel"
  done

  if ! (cd "$expect_root" && git apply --check -p1 "$patch_abs") >/dev/null 2>&1; then
    echo "  refuse recovery: cannot materialize expected tree from patch" >&2
    return 1
  fi
  (cd "$expect_root" && git apply -p1 "$patch_abs")
}

# Safe recovery for leftover untracked patch outputs after submodule --force.
# Deletes only when:
#   - tracked patch targets are clean vs HEAD
#   - complete set of new files exists
#   - each is untracked
#   - each byte-matches the content the patch would create on HEAD
# Otherwise fails with no writes.
try_recover_ssh_gate_leftovers() {
  local patch="$1"
  local rel
  local expect_root
  local missing=0

  for rel in "${SSH_GATE_TRACKED[@]}"; do
    if path_is_dirty "$rel"; then
      echo "  refuse recovery: tracked file has local changes: $rel" >&2
      return 1
    fi
  done

  for rel in "${SSH_GATE_NEW_FILES[@]}"; do
    if [[ ! -e "$CLASH/$rel" ]]; then
      echo "  refuse recovery: incomplete leftover set, missing: $rel" >&2
      missing=1
      continue
    fi
    if path_is_tracked "$rel"; then
      echo "  refuse recovery: would remove tracked file: $rel" >&2
      return 1
    fi
  done
  if ((missing)); then
    return 1
  fi

  expect_root="$(mktemp -d "${TMPDIR:-/tmp}/mihomo-ssh-gate-expect.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf '$expect_root'" RETURN

  if ! build_expected_ssh_gate_tree "$expect_root" "$patch"; then
    return 1
  fi

  for rel in "${SSH_GATE_NEW_FILES[@]}"; do
    if [[ ! -f "$expect_root/$rel" ]]; then
      echo "  refuse recovery: expected tree missing $rel (patch changed?)" >&2
      return 1
    fi
    if ! cmp -s "$CLASH/$rel" "$expect_root/$rel"; then
      echo "  refuse recovery: untracked file differs from patch output: $rel" >&2
      return 1
    fi
  done

  # Content-verified complete leftover set — safe to remove, then re-apply.
  for rel in "${SSH_GATE_NEW_FILES[@]}"; do
    rm -f "$CLASH/$rel"
  done
  return 0
}

apply_one() {
  local patch="$1"
  local name
  name="$(basename "$patch")"

  # Already applied: reverse dry-run succeeds.
  if git -C "$CLASH" apply --reverse --check "$patch" >/dev/null 2>&1; then
    echo "already applied: $name"
    return 0
  fi

  if git -C "$CLASH" apply --check "$patch" >/dev/null 2>&1; then
    git -C "$CLASH" apply "$patch"
    echo "applied: $name"
    return 0
  fi

  # Partial state: untracked stubs left after submodule update --force while
  # tracked files were reset to upstream. Recover only when leftovers are the
  # exact complete set the patch creates.
  if [[ "$name" == *disable-ssh-outbound* ]]; then
    echo "attempting safe recovery of SSH gate leftovers: $name"
    if try_recover_ssh_gate_leftovers "$patch"; then
      if git -C "$CLASH" apply --check "$patch" >/dev/null 2>&1; then
        git -C "$CLASH" apply "$patch"
        echo "applied: $name"
        return 0
      fi
      fail_apply "$name" "recovery cleaned verified leftovers but patch still does not apply"
    fi
    fail_apply "$name" \
      "recovery refused (dirty tracked, incomplete set, content mismatch, or tracked outputs)" \
      "fix local changes or reset submodule, then re-run"
  fi

  fail_apply "$name"
}

for p in "${patches[@]}"; do
  apply_one "$p"
done
