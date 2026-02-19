#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/ci/find-orphans.sh --environment <dev|prod> --tf-dir <path> [options]

Options:
  --environment <env>      Target environment (dev|prod).
  --project <name>         Project tag value (default: cloudradar).
  --tf-dir <path>          Terraform root (required, used to read state).
  --mode <pre-deploy|post-destroy>
                           Scan mode (default: pre-deploy).
  --strict <true|false>    Exit non-zero on findings (default: true in pre-deploy, false in post-destroy).
  --managed-by <value>     managed-by tag value (default: terraform).
  --summary-path <path>    Markdown summary output path (e.g. $GITHUB_STEP_SUMMARY).
USAGE
}

ENVIRONMENT=""
PROJECT="cloudradar"
TF_DIR=""
MODE="pre-deploy"
STRICT=""
MANAGED_BY="terraform"
SUMMARY_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      ENVIRONMENT="${2:-}"
      shift 2
      ;;
    --project)
      PROJECT="${2:-}"
      shift 2
      ;;
    --tf-dir)
      TF_DIR="${2:-}"
      shift 2
      ;;
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
    --strict)
      STRICT="${2:-}"
      shift 2
      ;;
    --managed-by)
      MANAGED_BY="${2:-}"
      shift 2
      ;;
    --summary-path)
      SUMMARY_PATH="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${ENVIRONMENT}" ]]; then
  echo "--environment is required" >&2
  exit 2
fi

if [[ -z "${TF_DIR}" ]]; then
  echo "--tf-dir is required" >&2
  exit 2
fi

if [[ "${MODE}" == "preflight" ]]; then
  # Backward-compatible alias.
  MODE="pre-deploy"
fi

if [[ "${MODE}" != "pre-deploy" && "${MODE}" != "post-destroy" ]]; then
  echo "--mode must be pre-deploy or post-destroy" >&2
  exit 2
fi

if [[ -z "${STRICT}" ]]; then
  if [[ "${MODE}" == "pre-deploy" ]]; then
    STRICT="true"
  else
    STRICT="false"
  fi
fi

if [[ "${STRICT}" != "true" && "${STRICT}" != "false" ]]; then
  echo "--strict must be true or false" >&2
  exit 2
fi

for bin in aws terraform jq sort comm; do
  if ! command -v "${bin}" >/dev/null 2>&1; then
    echo "${bin} is required" >&2
    exit 2
  fi
done

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

state_json="${tmp_dir}/state.json"
state_arns="${tmp_dir}/state_arns.txt"
tagged_arns="${tmp_dir}/tagged_arns.txt"
tagged_tmp="${tmp_dir}/tagged_raw.txt"
findings_file="${tmp_dir}/findings.txt"

terraform -chdir="${TF_DIR}" show -json > "${state_json}"

jq -r '
  def all_resources(m):
    (m.resources // [])[],
    ((m.child_modules // [])[] | all_resources(.));

  .values.root_module as $root
  | all_resources($root)
  | select(.mode == "managed")
  | select(.type | startswith("aws_"))
  | .values.arn? // empty
' "${state_json}" \
  | sed '/^$/d' \
  | sort -u > "${state_arns}"

: > "${tagged_tmp}"
pagination_token=""

while :; do
  if [[ -n "${pagination_token}" ]]; then
    response="$(aws resourcegroupstaggingapi get-resources \
      --tag-filters "Key=managed-by,Values=${MANAGED_BY}" "Key=Project,Values=${PROJECT}" "Key=Environment,Values=${ENVIRONMENT}" \
      --resources-per-page 100 \
      --pagination-token "${pagination_token}" \
      --output json)"
  else
    response="$(aws resourcegroupstaggingapi get-resources \
      --tag-filters "Key=managed-by,Values=${MANAGED_BY}" "Key=Project,Values=${PROJECT}" "Key=Environment,Values=${ENVIRONMENT}" \
      --resources-per-page 100 \
      --output json)"
  fi

  jq -r '.ResourceTagMappingList[].ResourceARN // empty' <<< "${response}" >> "${tagged_tmp}"
  pagination_token="$(jq -r '.PaginationToken // empty' <<< "${response}")"

  if [[ -z "${pagination_token}" ]]; then
    break
  fi
done

sort -u "${tagged_tmp}" > "${tagged_arns}"

comm -23 "${tagged_arns}" "${state_arns}" > "${findings_file}" || true

state_count="$(wc -l < "${state_arns}" | tr -d ' ')"
tagged_count="$(wc -l < "${tagged_arns}" | tr -d ' ')"
finding_count="$(wc -l < "${findings_file}" | tr -d ' ')"

status="PASS"
if (( finding_count > 0 )); then
  status="FAIL"
fi

echo "orphan-scan mode=${MODE} env=${ENVIRONMENT} status=${status}"
echo "info: terraform_state_arn_count=${state_count}"
echo "info: aws_tagged_arn_count=${tagged_count}"
if (( finding_count > 0 )); then
  while IFS= read -r arn; do
    echo "finding: tagged resource not found in Terraform state: ${arn}"
  done < "${findings_file}"
fi

if [[ -n "${SUMMARY_PATH}" ]]; then
  {
    echo "### orphan-scan (${MODE})"
    echo "- Status: \`${status}\`"
    echo "- Environment: \`${ENVIRONMENT}\`"
    echo "- Strict mode: \`${STRICT}\`"
    echo "- Comparison: \`AWS(tagged managed-by=${MANAGED_BY}, Project=${PROJECT}, Environment=${ENVIRONMENT}) - Terraform state ARNs\`"
    echo "- Terraform state ARNs: \`${state_count}\`"
    echo "- AWS tagged ARNs: \`${tagged_count}\`"
    echo "- Findings: \`${finding_count}\`"
    if (( finding_count == 0 )); then
      echo "- Result: no tagged-orphan resources detected."
    else
      echo "- Result:"
      while IFS= read -r arn; do
        echo "  - tagged resource not found in Terraform state: ${arn}"
      done < "${findings_file}"
    fi
  } >> "${SUMMARY_PATH}"
fi

if [[ "${STRICT}" == "true" && "${status}" == "FAIL" ]]; then
  exit 1
fi

exit 0
