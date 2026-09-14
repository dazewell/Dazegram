#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORKFLOW=$(cd "$SCRIPT_DIR/.." && pwd)/workflows/commit-tag.yml
source "$SCRIPT_DIR/parent-anchor-certify.sh"

ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
ORIGIN="$ROOT/origin.git"
REPO="$ROOT/work"

git init --quiet --bare "$ORIGIN"
git init --quiet "$REPO"
git -C "$REPO" config user.email "fixture@dazegram.local"
git -C "$REPO" config user.name "fixture"
git -C "$REPO" config core.autocrlf false
printf 'catalog\n' > "$REPO/FEATURES.md"
printf 'anchor\n' > "$REPO/anchor.txt"
git -C "$REPO" add FEATURES.md anchor.txt
git -C "$REPO" commit --quiet -m "anchor fixture"
NBASE=$(git -C "$REPO" rev-parse HEAD)
git -C "$REPO" branch nbase "$NBASE"
printf 'dev\n' > "$REPO/dev.txt"
git -C "$REPO" add dev.txt
git -C "$REPO" commit --quiet -m "dev fixture"
DEV=$(git -C "$REPO" rev-parse HEAD)
git -C "$REPO" branch dev "$DEV"
git -C "$REPO" remote add origin "$ORIGIN"
git -C "$REPO" push --quiet origin "$DEV:refs/heads/dev" "$NBASE:refs/heads/nbase"

DEV_TREE=$(git -C "$REPO" rev-parse "$DEV^{tree}")
NBASE_TREE=$(git -C "$REPO" rev-parse "$NBASE^{tree}")
S=$(printf 'snapshot fixture #infra\n' | git -C "$REPO" commit-tree "$NBASE_TREE" -p "$NBASE")
M=$(printf 'merge fixture\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$DEV" -p "$S")
cd "$REPO"

extract_validator() {
  awk '
    $0 == "      - name: Verify commit tags" { step = 1; next }
    step && $0 == "        run: |" { body = 1; next }
    body && $0 == "" { print; next }
    body && $0 ~ /^          / { print substr($0, 11); next }
    body { exit }
  ' "$WORKFLOW"
}

VALIDATOR=$(extract_validator)
[[ -n "$VALIDATOR" ]] || {
  echo "FAIL: could not extract the commit-tag validator" >&2
  exit 1
}

assert_rejects() {
  local name=$1 pattern=$2
  shift 2
  local output status
  set +e
  output=$("$@" 2>&1)
  status=$?
  set -e
  if [[ $status -eq 0 ]]; then
    echo "FAIL: $name unexpectedly passed" >&2
    exit 1
  fi
  if [[ "$output" != *"$pattern"* ]]; then
    echo "FAIL: $name rejected for the wrong reason" >&2
    echo "expected: $pattern" >&2
    echo "$output" >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$name"
}

assert_passes() {
  local name=$1
  shift
  local output
  if ! output=$("$@" 2>&1); then
    echo "FAIL: $name unexpectedly failed" >&2
    echo "$output" >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$name"
}

wrong_branch() (
  BRANCH_NAME=parent-anchor-certify
  require_branch_name
)

underscore_branch() (
  BRANCH_NAME=2026-09-13_parent-anchor-certify
  require_branch_name
)

dev_not_ancestor() (
  DEV=$DEV
  HEAD=$(printf 'unrelated\n' | git -C "$REPO" commit-tree "$DEV_TREE")
  require_dev_ancestor
)

empty_range() (
  DEV=$DEV
  HEAD=$DEV
  require_two_commit_range
)

extra_range() (
  DEV=$DEV
  local x1 x2
  x1=$(printf 'extra one\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$DEV")
  x2=$(printf 'extra two\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$x1")
  HEAD=$(printf 'extra three\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$x2")
  require_two_commit_range
)

head_tree_differs() (
  DEV=$DEV
  HEAD=$(printf 'changed tree\n' | git -C "$REPO" commit-tree "$NBASE_TREE" -p "$DEV")
  require_head_tree_matches_dev
)

merge_count_wrong() (
  DEV=$DEV
  local x1
  x1=$(printf 'plain one\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$DEV")
  HEAD=$(printf 'plain two\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$x1")
  resolve_merge_commit
)

merge_not_head() (
  M=$M
  HEAD=$S
  require_merge_is_head
)

merge_parent_order_wrong() (
  DEV=$DEV
  M=$(printf 'wrong order\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$S" -p "$DEV")
  require_merge_parents
)

merge_parent_count_wrong() (
  DEV=$DEV
  M=$(printf 'three parents\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$DEV" -p "$S" -p "$NBASE")
  require_merge_parents
)

merge_tree_mismatch() (
  DEV=$DEV
  M=$(printf 'wrong merge tree\n' | git -C "$REPO" commit-tree "$NBASE_TREE" -p "$DEV" -p "$S")
  require_merge_tree_matches_dev
)

snapshot_parent_wrong() (
  NBASE=$NBASE
  S=$(printf 'wrong snapshot parent\n' | git -C "$REPO" commit-tree "$NBASE_TREE" -p "$DEV")
  require_snapshot_parent
)

snapshot_parent_count_wrong() (
  NBASE=$NBASE
  S=$(printf 'two snapshot parents\n' | git -C "$REPO" commit-tree "$NBASE_TREE" -p "$NBASE" -p "$DEV")
  require_snapshot_parent
)

commit_set_wrong() (
  local extra
  extra=$(printf 'wrong set\n' | git -C "$REPO" commit-tree "$DEV_TREE" -p "$DEV")
  M=$M
  S=$S
  RANGE_COMMITS=("$M" "$extra")
  require_exact_commit_set
)

valid_topology() (
  cd "$REPO"
  local env_file="$ROOT/github-env"
  : > "$env_file"
  GITHUB_REF_NAME=2026-09-13-parent-anchor-certify \
    GITHUB_SHA=$M \
    GITHUB_ENV=$env_file \
    main
  grep -Fxq "BASE_SHA=$DEV" "$env_file"
  grep -Fxq "HEAD_SHA=$M" "$env_file"
)

valid_validator() (
  cd "$REPO"
  BASE_SHA=$DEV HEAD_SHA=$M bash -c "$VALIDATOR"
)

untagged_snapshot() (
  cd "$REPO"
  local untagged snapshot_merge env_file="$ROOT/untagged-env"
  untagged=$(printf 'snapshot fixture without tag\n' | git commit-tree "$NBASE_TREE" -p "$NBASE")
  snapshot_merge=$(printf 'merge untagged fixture\n' | git commit-tree "$DEV_TREE" -p "$DEV" -p "$untagged")
  : > "$env_file"
  GITHUB_REF_NAME=2026-09-13-parent-anchor-certify \
    GITHUB_SHA=$snapshot_merge \
    GITHUB_ENV=$env_file \
    main
  BASE_SHA=$DEV HEAD_SHA=$snapshot_merge bash -c "$VALIDATOR"
)

assert_rejects "wrong branch name" "branch name must match" wrong_branch
assert_passes "underscore branch unit fixture" underscore_branch
assert_rejects "dev not ancestor" "not an ancestor" dev_not_ancestor
assert_rejects "empty range" "is empty" empty_range
assert_rejects "extra range" "exactly two commits" extra_range
assert_rejects "head tree differs" "GITHUB_SHA tree differs" head_tree_differs
assert_rejects "merge count wrong" "exactly one merge commit" merge_count_wrong
assert_rejects "merge is not head" "must equal GITHUB_SHA" merge_not_head
assert_rejects "merge parent order wrong" "first parent must equal" merge_parent_order_wrong
assert_rejects "merge parent count wrong" "exactly two parents" merge_parent_count_wrong
assert_rejects "merge tree mismatch" "merge commit tree must equal" merge_tree_mismatch
assert_rejects "snapshot parent wrong" "parent must equal live origin/nbase" snapshot_parent_wrong
assert_rejects "snapshot parent count wrong" "exactly one parent" snapshot_parent_count_wrong
assert_rejects "range contains an extra commit" "other than the merge and snapshot" commit_set_wrong
assert_passes "valid synthetic snapshot and merge" valid_topology
assert_passes "unchanged validator accepts tagged snapshot and exempts merge" valid_validator
assert_rejects "untagged snapshot" "missing a #<slug> change tag" untagged_snapshot

echo "All parent-anchor certification fixtures passed."
