#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '::error::%s\n' "$*" >&2
  exit 1
}

require_branch_name() {
  [[ "$BRANCH_NAME" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}[-_]parent-anchor-certify$ ]] ||
    fail "branch name must match YYYY-MM-DD-parent-anchor-certify"
}

fetch_live_refs() {
  git fetch --no-tags origin \
    '+refs/heads/dev:refs/remotes/origin/dev' \
    '+refs/heads/nbase:refs/remotes/origin/nbase'
  DEV=$(git rev-parse --verify 'refs/remotes/origin/dev^{commit}') ||
    fail "live origin/dev is not available"
  NBASE=$(git rev-parse --verify 'refs/remotes/origin/nbase^{commit}') ||
    fail "live origin/nbase is not available"
}

require_head_commit() {
  [[ "$HEAD" =~ ^[0-9a-f]{40}$ ]] ||
    fail "GITHUB_SHA is not a full commit SHA"
  git cat-file -e "$HEAD^{commit}" ||
    fail "GITHUB_SHA is not an available commit"
}

require_dev_ancestor() {
  git merge-base --is-ancestor "$DEV" "$HEAD" ||
    fail "live origin/dev is not an ancestor of GITHUB_SHA"
}

require_two_commit_range() {
  mapfile -t RANGE_COMMITS < <(git rev-list "$DEV..$HEAD")
  if [[ ${#RANGE_COMMITS[@]} -eq 0 ]]; then
    fail "live origin/dev..GITHUB_SHA is empty"
  fi
  [[ ${#RANGE_COMMITS[@]} -eq 2 ]] ||
    fail "live origin/dev..GITHUB_SHA must contain exactly two commits"
}

require_head_tree_matches_dev() {
  git diff --quiet "$DEV" "$HEAD" -- ||
    fail "GITHUB_SHA tree differs from live origin/dev"
}

resolve_merge_commit() {
  mapfile -t MERGES < <(git rev-list --merges "$DEV..$HEAD")
  [[ ${#MERGES[@]} -eq 1 ]] ||
    fail "certification range must contain exactly one merge commit"
  M=${MERGES[0]}
}

require_merge_is_head() {
  [[ "$M" == "$HEAD" ]] ||
    fail "the merge commit must equal GITHUB_SHA"
}

require_merge_parents() {
  local merge_line
  read -r -a merge_line <<< "$(git rev-list --parents -n 1 "$M")"
  [[ ${#merge_line[@]} -eq 3 ]] ||
    fail "the merge commit must have exactly two parents"
  [[ "${merge_line[1]}" == "$DEV" ]] ||
    fail "the merge commit first parent must equal live origin/dev"
  S=${merge_line[2]}
}

require_merge_tree_matches_dev() {
  local merge_tree dev_tree
  merge_tree=$(git rev-parse "$M^{tree}")
  dev_tree=$(git rev-parse "$DEV^{tree}")
  [[ "$merge_tree" == "$dev_tree" ]] ||
    fail "the merge commit tree must equal live origin/dev"
}

require_snapshot_parent() {
  local snapshot_line
  read -r -a snapshot_line <<< "$(git rev-list --parents -n 1 "$S")"
  [[ ${#snapshot_line[@]} -eq 2 ]] ||
    fail "the snapshot commit must have exactly one parent"
  [[ "${snapshot_line[1]}" == "$NBASE" ]] ||
    fail "the snapshot commit parent must equal live origin/nbase"
}

require_exact_commit_set() {
  local commit seen_merge=0 seen_snapshot=0
  for commit in "${RANGE_COMMITS[@]}"; do
    case "$commit" in
      "$M") seen_merge=1 ;;
      "$S") seen_snapshot=1 ;;
      *) fail "certification range contains a commit other than the merge and snapshot" ;;
    esac
  done
  [[ "$M" != "$S" && $seen_merge -eq 1 && $seen_snapshot -eq 1 ]] ||
    fail "certification range must be exactly the merge and snapshot commits"
}

export_range() {
  [[ -n "${GITHUB_ENV:-}" ]] ||
    fail "GITHUB_ENV is not available"
  {
    printf 'BASE_SHA=%s\n' "$DEV"
    printf 'HEAD_SHA=%s\n' "$HEAD"
  } >> "$GITHUB_ENV"
}

main() {
  BRANCH_NAME=${GITHUB_REF_NAME:-}
  HEAD=${GITHUB_SHA:-}

  require_branch_name
  fetch_live_refs
  require_head_commit
  require_dev_ancestor
  require_two_commit_range
  require_head_tree_matches_dev
  resolve_merge_commit
  require_merge_is_head
  require_merge_parents
  require_merge_tree_matches_dev
  require_snapshot_parent
  require_exact_commit_set
  export_range
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
