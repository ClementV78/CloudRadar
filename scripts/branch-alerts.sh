#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
alerts_dir="${repo_root}/.local/alerts"
summary_file="${alerts_dir}/summary.alert"
main_ref="origin/main"
now_epoch="$(date +%s)"
max_age_days=3
gh_enabled=false

mkdir -p "${alerts_dir}"

git -C "${repo_root}" fetch -q origin main

if command -v gh >/dev/null 2>&1; then
  if gh auth status -h github.com >/dev/null 2>&1; then
    gh_enabled=true
  fi
fi

rm -f "${alerts_dir}"/branch-*.alert

alert_count=0
summary_tmp="${alerts_dir}/.summary.tmp"
printf 'Branch alerts summary\n' > "${summary_tmp}"
printf 'Checked at: %s\n\n' "$(date -u +"%Y-%m-%d %H:%M:%S UTC")" >> "${summary_tmp}"

while IFS= read -r branch; do
  if [[ "${branch}" == "main" ]]; then
    continue
  fi

  branch_ref="refs/heads/${branch}"
  if ! git -C "${repo_root}" show-ref --verify --quiet "${branch_ref}"; then
    continue
  fi

  upstream_ref="$(
    git -C "${repo_root}" rev-parse --abbrev-ref "${branch}@{upstream}" 2>/dev/null || true
  )"
  if git -C "${repo_root}" merge-base --is-ancestor "${branch}" "${main_ref}" 2>/dev/null; then
    continue
  fi
  read -r ahead behind < <(
    git -C "${repo_root}" rev-list --left-right --count "${branch}...${main_ref}"
  )

  branch_epoch="$(
    git -C "${repo_root}" log -1 --format=%ct "${branch}"
  )"
  age_days=$(((now_epoch - branch_epoch) / 86400))

  reasons=()
  if [[ -z "${upstream_ref}" ]]; then
    reasons+=("no-upstream")
  fi
  latest_pr_number=""
  latest_pr_merged_at=""
  latest_pr_merged_epoch=0
  if [[ "${gh_enabled}" == "true" ]]; then
    pr_info="$(
      gh pr list \
        --state merged \
        --head "${branch}" \
        --json number,mergedAt \
        --jq 'sort_by(.mergedAt) | last | [.number, .mergedAt] | @tsv' 2>/dev/null || true
    )"
    if [[ -n "${pr_info}" ]]; then
      latest_pr_number="${pr_info%%$'\t'*}"
      latest_pr_merged_at="${pr_info#*$'\t'}"
      if [[ -n "${latest_pr_merged_at}" ]]; then
        latest_pr_merged_epoch="$(date -d "${latest_pr_merged_at}" +%s)"
      fi
    fi
  fi

  merged_without_new_commits=false
  if [[ "${latest_pr_merged_epoch}" -gt 0 && "${branch_epoch}" -le "${latest_pr_merged_epoch}" ]]; then
    merged_without_new_commits=true
  fi

  if [[ "${merged_without_new_commits}" == "false" ]]; then
    if [[ "${latest_pr_merged_epoch}" -gt 0 && "${branch_epoch}" -gt "${latest_pr_merged_epoch}" ]]; then
      reasons+=("commits-after-merge")
    fi
    if [[ "${behind}" -gt 0 ]]; then
      reasons+=("behind-main=${behind}")
    fi
    if [[ "${age_days}" -gt "${max_age_days}" ]]; then
      reasons+=("age-days=${age_days}")
    fi
  fi

  if [[ "${#reasons[@]}" -gt 0 ]]; then
    alert_count=$((alert_count + 1))
    {
      printf 'Branch: %s\n' "${branch}"
      printf 'Reasons: %s\n' "$(IFS=, ; echo "${reasons[*]}")"
      if [[ -z "${upstream_ref}" ]]; then
        printf 'Upstream: none\n'
      else
        printf 'Upstream: %s\n' "${upstream_ref}"
      fi
      if [[ -n "${latest_pr_number}" && -n "${latest_pr_merged_at}" ]]; then
        printf 'Latest merged PR: #%s at %s\n' "${latest_pr_number}" "${latest_pr_merged_at}"
      elif [[ "${gh_enabled}" == "true" ]]; then
        printf 'Latest merged PR: none\n'
      else
        printf 'Latest merged PR: unavailable (gh auth missing)\n'
      fi
      printf 'Behind origin/main: %s commits\n' "${behind}"
      printf 'Ahead of origin/main: %s commits\n' "${ahead}"
      printf 'Age: %s days\n\n' "${age_days}"
    } >> "${summary_tmp}"
  fi
done < <(git -C "${repo_root}" for-each-ref --format='%(refname:short)' refs/heads/)

if [[ "${alert_count}" -gt 0 ]]; then
  mv "${summary_tmp}" "${summary_file}"
else
  rm -f "${summary_tmp}" "${summary_file}"
fi
