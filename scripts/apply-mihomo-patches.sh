#!/usr/bin/env bash
# Apply parent-tracked Mihomo (clash-foss) product patches.
# Safe to re-run (idempotent). Required after clean submodule checkout.
#
# Source of truth is core/patches/mihomo/*.patch in the parent repo — not the
# submodule gitlink. A clean `git submodule update --force` alone does NOT
# enable product gates (e.g. no_ssh).
#
# Usage:
#   ./scripts/apply-mihomo-patches.sh           # apply (default)
#   ./scripts/apply-mihomo-patches.sh --verify  # check working tree == patch result
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASH="$ROOT/core/src/foss/golang/clash"
PATCH_DIR="$ROOT/core/patches/mihomo"

MODE="apply"
if [[ "${1:-}" == "--verify" ]]; then
  MODE="verify"
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--verify]" >&2
  exit 2
fi

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
  if [[ "$MODE" == "verify" ]]; then
    echo "error: no mihomo patches in $PATCH_DIR (nothing to verify)" >&2
    exit 1
  fi
  echo "no mihomo patches in $PATCH_DIR"
  exit 0
fi

# Paths from 0001-disable-ssh-outbound-no_ssh.patch
# Shared source for apply recovery and --verify (do not duplicate elsewhere).
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

# Sorted unique lines on stdin → stdout.
sort_unique() {
  sort -u
}

# True if $1 (newline-separated paths) equals the bash array named by $2.
# Prints extras/missing on mismatch.
set_equals_array() {
  local actual_blob="$1"
  local -n expected_arr="$2"
  local label="$3"
  local expected_blob
  local extra missing

  expected_blob="$(printf '%s\n' "${expected_arr[@]}" | sort_unique)"
  actual_blob="$(printf '%s\n' "$actual_blob" | sed '/^$/d' | sort_unique)"

  extra="$(comm -13 <(printf '%s\n' "$expected_blob") <(printf '%s\n' "$actual_blob") || true)"
  missing="$(comm -23 <(printf '%s\n' "$expected_blob") <(printf '%s\n' "$actual_blob") || true)"

  if [[ -n "$extra" || -n "$missing" ]]; then
    echo "error: $label set mismatch in $CLASH" >&2
    if [[ -n "$extra" ]]; then
      echo "  unexpected:" >&2
      while IFS= read -r line; do
        printf '    %s\n' "$line" >&2
      done <<<"$extra"
    fi
    if [[ -n "$missing" ]]; then
      echo "  missing:" >&2
      while IFS= read -r line; do
        printf '    %s\n' "$line" >&2
      done <<<"$missing"
    fi
    return 1
  fi
  return 0
}

# Verify working tree is exactly the SSH-gate patch result: no extra dirty/untracked
# paths, and every gate file byte-matches materializing the patch on HEAD.
verify_ssh_gate_tree() {
  local patch=""
  local p name rel
  local dirty_tracked untracked
  local expect_root
  local failed=0

  for p in "${patches[@]}"; do
    name="$(basename "$p")"
    if [[ "$name" == *disable-ssh-outbound* ]]; then
      patch="$p"
      break
    fi
  done
  if [[ -z "$patch" ]]; then
    echo "error: SSH gate patch not found in $PATCH_DIR" >&2
    exit 1
  fi

  dirty_tracked="$(
    {
      git -C "$CLASH" diff --name-only HEAD
      git -C "$CLASH" diff --name-only --cached
    } | sed '/^$/d' | sort_unique
  )"
  untracked="$(git -C "$CLASH" ls-files --others --exclude-standard | sed '/^$/d' | sort_unique)"

  if ! set_equals_array "$dirty_tracked" SSH_GATE_TRACKED "dirty tracked"; then
    failed=1
  fi
  if ! set_equals_array "$untracked" SSH_GATE_NEW_FILES "untracked"; then
    failed=1
  fi
  if ((failed)); then
    echo "error: submodule working tree is not exactly the product patch result" >&2
    echo "  run: ./scripts/apply-mihomo-patches.sh  (after clean gitlink checkout)" >&2
    git -C "$CLASH" status --short >&2 || true
    exit 1
  fi

  expect_root="$(mktemp -d "${TMPDIR:-/tmp}/mihomo-ssh-gate-verify.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf '$expect_root'" RETURN

  if ! build_expected_ssh_gate_tree "$expect_root" "$patch"; then
    echo "error: cannot materialize expected SSH gate tree from patch on HEAD" >&2
    exit 1
  fi

  for rel in "${SSH_GATE_TRACKED[@]}" "${SSH_GATE_NEW_FILES[@]}"; do
    if [[ ! -f "$expect_root/$rel" ]]; then
      echo "error: expected tree missing $rel (patch changed?)" >&2
      failed=1
      continue
    fi
    if [[ ! -f "$CLASH/$rel" ]]; then
      echo "error: working tree missing $rel" >&2
      failed=1
      continue
    fi
    if ! cmp -s "$CLASH/$rel" "$expect_root/$rel"; then
      echo "error: content differs from patch output: $rel" >&2
      failed=1
    fi
  done

  if ((failed)); then
    echo "error: SSH gate file contents do not match patch applied to HEAD" >&2
    exit 1
  fi

  echo "ok: submodule working tree matches product patches"
}

if [[ "$MODE" == "verify" ]]; then
  verify_ssh_gate_tree
  exit 0
fi

for p in "${patches[@]}"; do
  apply_one "$p"
done
