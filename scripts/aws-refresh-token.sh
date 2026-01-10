#!/usr/bin/env bash

# Refresh AWS session credentials by assuming a role with MFA.
# Usage (recommended): source scripts/aws-refresh-token.sh <MFA_CODE>

is_sourced=0
if [[ -n "${BASH_SOURCE:-}" ]]; then
  if [[ "${BASH_SOURCE[0]-}" != "${0}" ]]; then
    is_sourced=1
  fi
elif [[ -n "${ZSH_EVAL_CONTEXT:-}" ]]; then
  if [[ "${ZSH_EVAL_CONTEXT}" == *:file ]]; then
    is_sourced=1
  fi
fi

if [[ "${is_sourced}" -ne 1 ]]; then
  echo "Note: source this script to export credentials in your current shell."
  echo "Example: source scripts/aws-refresh-token.sh 123456"
fi

script_path=""
if [[ -n "${ZSH_VERSION:-}" ]]; then
  script_path="${(%):-%x}"
else
  script_path="${BASH_SOURCE[0]}"
fi
script_dir="$(cd "$(dirname "${script_path}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

if [[ -f "${repo_root}/.env" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${repo_root}/.env"
  set +a
fi


PROFILE="${PROFILE:-${AWS_PROFILE:-cloudradar-bootstrap}}"
ACCOUNT_ID="${ACCOUNT_ID:-}"
ROLE_ARN="${ROLE_ARN:-}"
MFA_DEVICE_NAME="${MFA_DEVICE_NAME:-}"
MFA_ARN="${MFA_ARN:-}"
SESSION_NAME="${SESSION_NAME:-cloudradar-bootstrap}"
DURATION_SECONDS="${DURATION_SECONDS:-3600}"

if [[ -n "${MFA_DEVICE_NAME}" ]]; then
  if [[ -z "${ACCOUNT_ID}" ]]; then
    echo "Missing ACCOUNT_ID (or set MFA_ARN directly)."
    return 1
  fi
  MFA_ARN="arn:aws:iam::${ACCOUNT_ID}:mfa/${MFA_DEVICE_NAME}"
elif [[ -z "${MFA_ARN}" ]]; then
  echo "Missing MFA_DEVICE_NAME (or set MFA_ARN directly)."
  return 1
fi

if [[ -z "${ROLE_ARN}" ]]; then
  if [[ -z "${ACCOUNT_ID}" ]]; then
    echo "Missing ACCOUNT_ID (or set ROLE_ARN directly)."
    return 1
  fi
  ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/CloudRadarBootstrapRole"
fi

token_code="${1:-}"
if [[ -z "${token_code}" ]]; then
  printf "%s" "MFA code: "
  read -r token_code
fi

if [[ -z "${token_code}" ]]; then
  echo "MFA code is required."
  return 1
fi

aws_cmd=(
  aws sts assume-role
  --profile "${PROFILE}"
  --role-arn "${ROLE_ARN}"
  --role-session-name "${SESSION_NAME}"
  --serial-number "${MFA_ARN}"
  --token-code "${token_code}"
  --duration-seconds "${DURATION_SECONDS}"
  --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]"
  --output text
)

if ! creds="$("${aws_cmd[@]}")"; then
  echo ""
  echo "Debug command (replace <MFA_CODE>):"
  echo "aws sts assume-role --profile \"${PROFILE}\" --role-arn \"${ROLE_ARN}\" --role-session-name \"${SESSION_NAME}\" --serial-number \"${MFA_ARN}\" --token-code <MFA_CODE> --duration-seconds \"${DURATION_SECONDS}\" --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text"
  return 1
fi

read -r AK SK ST EXPIRATION <<< "${creds}"

export AWS_ACCESS_KEY_ID="${AK}"
export AWS_SECRET_ACCESS_KEY="${SK}"
export AWS_SESSION_TOKEN="${ST}"

echo "AssumeRole succeeded for ${ROLE_ARN}."
echo "Credentials exported in current shell."
echo "Session expires at: ${EXPIRATION}"
echo "To refresh again: source scripts/aws-refresh-token.sh <MFA_CODE>"
