#!/usr/bin/env bash
set -euo pipefail

# Git backup helper: push commits, create a tag, push the tag, and build a bundle.
# Tag format: <project>-<milestone>-<YYYY-MM-DD>
# Bundle filename: <tag>.bundle

usage() {
  cat <<'USAGE'
Usage:
  scripts/git-backup.sh [--milestone <name>] [--date <YYYY-MM-DD>] [--dry-run]

Options:
  --milestone  Override milestone name (e.g. v1-mvp). If omitted, script tries to fetch the current open milestone via gh.
  --date       Override date (default: today).
  --dry-run    Print actions without executing.
USAGE
}

die() {
  echo "Error: $1" >&2
  exit 1
}

dry_run=false
milestone_override=""
date_override=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --milestone)
      milestone_override="$2"
      shift 2
      ;;
    --date)
      date_override="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Not a git repository."
cd "$repo_root"

project_name="$(basename "$repo_root" | tr '[:upper:]' '[:lower:]')"

branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Warning: working tree has uncommitted changes (not included in tag/bundle)."
fi

if git remote get-url origin >/dev/null 2>&1; then
  git fetch origin --tags >/dev/null 2>&1 || true
fi

if [[ -z "$date_override" ]]; then
  date_override="$(date +%Y-%m-%d)"
fi

milestone="$milestone_override"
if [[ -z "$milestone" ]]; then
  if command -v gh >/dev/null 2>&1; then
    repo_slug="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)"
    if [[ -n "$repo_slug" ]]; then
      milestone="$(gh api "repos/$repo_slug/milestones?state=open" --jq 'sort_by(.due_on // "9999-12-31T00:00:00Z") | .[0].title' 2>/dev/null || true)"
    fi
  fi
fi

if [[ -z "$milestone" || "$milestone" == "null" ]]; then
  milestone="no-milestone"
fi

milestone_slug="$(echo "$milestone" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr -cd 'a-z0-9.-')"
tag_name="${project_name}-${milestone_slug}-${date_override}"
bundle_dir="${repo_root}/backups"
bundle_name="${bundle_dir}/${tag_name}.bundle"

if git rev-parse -q --verify "refs/tags/$tag_name" >/dev/null; then
  die "Tag already exists: $tag_name"
fi

if [[ "$branch" == "main" ]]; then
  if git rev-parse --verify origin/main >/dev/null 2>&1; then
    ahead_count="$(git rev-list --left-right --count origin/main...main | awk '{print $2}')"
    if [[ "$ahead_count" != "0" ]]; then
      die "main is ahead of origin/main; avoid direct push. Create a PR instead."
    fi
  fi
fi

run() {
  if $dry_run; then
    echo "[dry-run] $*"
  else
    eval "$@"
  fi
}

echo "Project: $project_name"
echo "Milestone: $milestone"
echo "Date: $date_override"
echo "Tag: $tag_name"
echo "Bundle: $bundle_name"

run "git push"
run "git tag $tag_name"
run "git push origin $tag_name"
run "mkdir -p \"$bundle_dir\""
run "git bundle create $bundle_name --all"
run "git bundle verify $bundle_name"

echo "Done."
