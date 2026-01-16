#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: bootstrap-argocd.sh [--env <env>] [--project <project>] <instance-id> [region] [argocd-version]

Example:
  scripts/bootstrap-argocd.sh i-0123456789abcdef us-east-1 3.2.5
  scripts/bootstrap-argocd.sh --env dev --project cloudradar us-east-1 3.2.5

Environment overrides:
  ARGOCD_NAMESPACE=argocd
  ARGOCD_APP_NAME=cloudradar
  ARGOCD_APP_NAMESPACE=cloudradar
  ARGOCD_APP_REPO=https://github.com/ClementV78/CloudRadar.git
  ARGOCD_APP_PATH=k8s/apps
  ARGOCD_APP_REVISION=main
USAGE
}

ENVIRONMENT=""
PROJECT=""
INSTANCE_ID=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      ENVIRONMENT="${2:-}"
      shift 2
      ;;
    --project)
      PROJECT="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -gt 0 ]]; then
  INSTANCE_ID="$1"
  shift
fi

if [[ -z "${INSTANCE_ID}" && -z "${ENVIRONMENT}" ]]; then
  usage
  exit 1
fi

REGION="${1:-${AWS_REGION:-us-east-1}}"
ARGOCD_VERSION="${2:-3.2.5}"
ARGOCD_NAMESPACE="${ARGOCD_NAMESPACE:-argocd}"
ARGOCD_APP_NAME="${ARGOCD_APP_NAME:-cloudradar}"
ARGOCD_APP_NAMESPACE="${ARGOCD_APP_NAMESPACE:-cloudradar}"
ARGOCD_APP_REPO="${ARGOCD_APP_REPO:-https://github.com/ClementV78/CloudRadar.git}"
ARGOCD_APP_PATH="${ARGOCD_APP_PATH:-k8s/apps}"
ARGOCD_APP_REVISION="${ARGOCD_APP_REVISION:-main}"

if ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI is required." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to build SSM parameters." >&2
  exit 1
fi

if [[ -z "${INSTANCE_ID}" ]]; then
  filters=(
    "Name=tag:Role,Values=k3s-server"
    "Name=tag:Environment,Values=${ENVIRONMENT}"
    "Name=instance-state-name,Values=running"
  )
  if [[ -n "${PROJECT}" ]]; then
    filters+=("Name=tag:Project,Values=${PROJECT}")
  fi

  INSTANCE_ID="$(aws ec2 describe-instances \
    --region "${REGION}" \
    --filters "${filters[@]}" \
    --query "Reservations[].Instances[].InstanceId" \
    --output text)"

  if [[ -z "${INSTANCE_ID}" || "${INSTANCE_ID}" == "None" ]]; then
    echo "No running k3s server instance found for env=${ENVIRONMENT} project=${PROJECT:-any}." >&2
    exit 1
  fi
fi

commands=(
  "set -euo pipefail"
  "sudo kubectl get namespace ${ARGOCD_NAMESPACE} >/dev/null 2>&1 || sudo kubectl create namespace ${ARGOCD_NAMESPACE}"
  "sudo kubectl -n ${ARGOCD_NAMESPACE} apply -f https://raw.githubusercontent.com/argoproj/argo-cd/v${ARGOCD_VERSION}/manifests/install.yaml"
  "sudo kubectl wait --for=condition=Established crd/applications.argoproj.io --timeout=120s"
  "sudo kubectl -n ${ARGOCD_NAMESPACE} wait --for=condition=Available deployment/argocd-server --timeout=300s"
  "sudo kubectl -n ${ARGOCD_NAMESPACE} get pods -o wide"
  "printf '%s\n' \"apiVersion: argoproj.io/v1alpha1\" \"kind: Application\" \"metadata:\" \"  name: ${ARGOCD_APP_NAME}\" \"  namespace: ${ARGOCD_NAMESPACE}\" \"spec:\" \"  project: default\" \"  source:\" \"    repoURL: ${ARGOCD_APP_REPO}\" \"    targetRevision: ${ARGOCD_APP_REVISION}\" \"    path: ${ARGOCD_APP_PATH}\" \"  destination:\" \"    server: https://kubernetes.default.svc\" \"    namespace: ${ARGOCD_APP_NAMESPACE}\" \"  syncPolicy:\" \"    syncOptions:\" \"      - CreateNamespace=true\" | sudo kubectl apply -f -"
)

ARGOCD_COMMANDS="$(printf "%s\n" "${commands[@]}")"
export ARGOCD_COMMANDS
params_json="$(python3 - <<'PY'
import json
import os

commands = os.environ["ARGOCD_COMMANDS"].splitlines()
print(json.dumps({"commands": commands}))
PY
)"

command_id="$(aws ssm send-command \
  --region "${REGION}" \
  --instance-ids "${INSTANCE_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "${params_json}" \
  --query "Command.CommandId" \
  --output text)"

aws ssm wait command-executed \
  --region "${REGION}" \
  --command-id "${command_id}" \
  --instance-id "${INSTANCE_ID}"

aws ssm get-command-invocation \
  --region "${REGION}" \
  --command-id "${command_id}" \
  --instance-id "${INSTANCE_ID}"
