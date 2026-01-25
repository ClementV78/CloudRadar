#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "" ]]; then
  echo "Usage: source scripts/get-aws-kubeconfig.sh <instance-id> [local-port]" >&2
  return 2 2>/dev/null || exit 2
fi

instance_id="$1"
local_port="${2:-16443}"
remote_host="127.0.0.1"
remote_port="6443"
kubeconfig_path="${KUBECONFIG_PATH:-/tmp/k3s-aws.yaml}"

command_id="$(aws ssm send-command \
  --instance-ids "${instance_id}" \
  --document-name AWS-RunShellScript \
  --parameters commands='["sudo cat /etc/rancher/k3s/k3s.yaml"]' \
  --query "Command.CommandId" \
  --output text)"

for _ in $(seq 1 30); do
  status="$(aws ssm get-command-invocation \
    --command-id "${command_id}" \
    --instance-id "${instance_id}" \
    --query "Status" \
    --output text)"
  case "${status}" in
    Success)
      break
      ;;
    Failed|Cancelled|TimedOut)
      echo "SSM command failed with status: ${status}" >&2
      return 1 2>/dev/null || exit 1
      ;;
  esac
  sleep 2
done

aws ssm get-command-invocation \
  --command-id "${command_id}" \
  --instance-id "${instance_id}" \
  --query "StandardOutputContent" \
  --output text > "${kubeconfig_path}"

sed -i "s#https://${remote_host}:${remote_port}#https://${remote_host}:${local_port}#" "${kubeconfig_path}"

sourced=0
if (return 0 2>/dev/null); then
  sourced=1
fi

if [[ "${sourced}" -eq 1 ]]; then
  export KUBECONFIG="${kubeconfig_path}"
  kubectl get nodes
else
  KUBECONFIG="${kubeconfig_path}" kubectl get nodes
  echo "Kubeconfig written to ${kubeconfig_path}. Run: export KUBECONFIG=${kubeconfig_path}" >&2
fi
