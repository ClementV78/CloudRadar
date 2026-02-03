#!/usr/bin/env bash
set -euo pipefail

require_cmd() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "${cmd} is required." >&2
    exit 1
  fi
}

is_instance_id() {
  [[ "$1" =~ ^i-[0-9a-f]{8}([0-9a-f]{9})?$ || "$1" =~ ^mi-[0-9a-f]{17}$ ]]
}

is_region() {
  [[ "$1" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]]
}

resolve_instance_id() {
  local region="$1"
  local environment="$2"
  local project="$3"

  local -a filters
  filters=(
    "Name=tag:Role,Values=k3s-server"
    "Name=tag:Environment,Values=${environment}"
    "Name=instance-state-name,Values=running"
  )

  if [[ -n "${project}" ]]; then
    filters+=("Name=tag:Project,Values=${project}")
  fi

  aws ec2 describe-instances \
    --region "${region}" \
    --filters "${filters[@]}" \
    --query "Reservations[].Instances[].InstanceId" \
    --output text
}

ensure_instance_id() {
  local region="$1"
  local environment="$2"
  local project="$3"
  local instance_id="$4"

  if [[ -n "${instance_id}" ]]; then
    echo "${instance_id}"
    return 0
  fi

  if [[ -z "${environment}" ]]; then
    echo "Either <instance-id> or --env is required." >&2
    exit 1
  fi

  local resolved
  resolved="$(resolve_instance_id "${region}" "${environment}" "${project}")"

  if [[ -z "${resolved}" || "${resolved}" == "None" ]]; then
    echo "No running k3s server instance found for env=${environment} project=${project:-any}." >&2
    exit 1
  fi

  set -- ${resolved}
  if [[ "$#" -gt 1 ]]; then
    echo "Warning: multiple k3s server instances found (${resolved}); using ${1}." >&2
  fi
  echo "${1}"
}

wait_for_ssm_command() {
  local region="$1"
  local command_id="$2"
  local instance_id="$3"
  local max_wait_seconds="${4:-900}"
  local sleep_seconds=10
  local elapsed=0

  while true; do
    local status
    status="$(aws ssm get-command-invocation \
      --region "${region}" \
      --command-id "${command_id}" \
      --instance-id "${instance_id}" \
      --query "Status" \
      --output text 2>/dev/null || true)"

    case "${status}" in
      Success)
        return 0
        ;;
      Failed|Cancelled|TimedOut|Cancelling)
        aws ssm get-command-invocation \
          --region "${region}" \
          --command-id "${command_id}" \
          --instance-id "${instance_id}"
        return 1
        ;;
      Pending|InProgress|Delayed|"")
        echo "Waiting for SSM command (status=${status:-unknown})..."
        ;;
      *)
        echo "Unexpected SSM status: ${status}" >&2
        ;;
    esac

    if [[ "${elapsed}" -ge "${max_wait_seconds}" ]]; then
      echo "SSM command ${command_id} did not finish within ${max_wait_seconds}s." >&2
      aws ssm get-command-invocation \
        --region "${region}" \
        --command-id "${command_id}" \
        --instance-id "${instance_id}"
      return 1
    fi

    sleep "${sleep_seconds}"
    elapsed=$((elapsed + sleep_seconds))
  done
}

ssm_run_commands() {
  local region="$1"
  local instance_id="$2"
  local timeout_seconds="$3"
  local notice_title="$4"
  local -n commands_ref="$5"

  local params_json
  params_json="$(printf "%s\n" "${commands_ref[@]}" | jq -Rn '{commands: [inputs]}')"

  local command_id
  command_id="$(aws ssm send-command \
    --region "${region}" \
    --instance-ids "${instance_id}" \
    --document-name "AWS-RunShellScript" \
    --parameters "${params_json}" \
    --timeout-seconds "${timeout_seconds}" \
    --query "Command.CommandId" \
    --output text)"

  echo "::notice title=SSM::${notice_title} command_id=${command_id}"

  wait_for_ssm_command "${region}" "${command_id}" "${instance_id}" "${timeout_seconds}"

  aws ssm get-command-invocation \
    --region "${region}" \
    --command-id "${command_id}" \
    --instance-id "${instance_id}"
}
