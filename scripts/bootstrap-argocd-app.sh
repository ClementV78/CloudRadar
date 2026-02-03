#!/usr/bin/env bash
set -euo pipefail

# Create or update an ArgoCD Application via SSM and optionally wait for CRDs.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/bootstrap-argocd-common.sh
source "${SCRIPT_DIR}/lib/bootstrap-argocd-common.sh"

usage() {
  cat <<'USAGE'
Usage: bootstrap-argocd-app.sh [--env <env>] [--project <project>] [--region <region>] [<instance-id>]

Example:
  scripts/bootstrap-argocd-app.sh i-0123456789abcdef us-east-1
  scripts/bootstrap-argocd-app.sh --env dev --project cloudradar --region us-east-1

Environment overrides:
  ARGOCD_NAMESPACE=argocd
  ARGOCD_APP_NAME=cloudradar
  ARGOCD_APP_NAMESPACE=cloudradar
  ARGOCD_APP_REPO=https://github.com/ClementV78/CloudRadar.git
  ARGOCD_APP_PATH=k8s/apps
  ARGOCD_APP_REVISION=main
  IGNORE_INGESTER_REPLICAS=false
  WAIT_CRDS=externalsecrets.external-secrets.io,clustersecretstores.external-secrets.io,secretstores.external-secrets.io
USAGE
}

ENVIRONMENT=""
PROJECT=""
INSTANCE_ID=""
REGION_OVERRIDE=""

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
    --region)
      REGION_OVERRIDE="${2:-}"
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
    -* )
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
  if is_instance_id "$1"; then
    INSTANCE_ID="$1"
    shift
  fi
fi

REGION="${REGION_OVERRIDE:-${AWS_REGION:-us-east-1}}"
if [[ -z "${REGION_OVERRIDE}" && $# -gt 0 ]]; then
  if is_region "$1"; then
    REGION="$1"
    shift
  fi
fi

ARGOCD_NAMESPACE="${ARGOCD_NAMESPACE:-argocd}"
ARGOCD_APP_NAME="${ARGOCD_APP_NAME:-cloudradar}"
ARGOCD_APP_NAMESPACE="${ARGOCD_APP_NAMESPACE:-cloudradar}"
ARGOCD_APP_REPO="${ARGOCD_APP_REPO:-https://github.com/ClementV78/CloudRadar.git}"
ARGOCD_APP_PATH="${ARGOCD_APP_PATH:-k8s/apps}"
ARGOCD_APP_REVISION="${ARGOCD_APP_REVISION:-main}"
IGNORE_INGESTER_REPLICAS="${IGNORE_INGESTER_REPLICAS:-false}"
WAIT_CRDS="${WAIT_CRDS:-}"

require_cmd aws
require_cmd jq

INSTANCE_ID="$(ensure_instance_id "${REGION}" "${ENVIRONMENT}" "${PROJECT}" "${INSTANCE_ID}")"

app_lines=(
  "apiVersion: argoproj.io/v1alpha1"
  "kind: Application"
  "metadata:"
  "  name: ${ARGOCD_APP_NAME}"
  "  namespace: ${ARGOCD_NAMESPACE}"
  "spec:"
  "  project: default"
  "  source:"
  "    repoURL: ${ARGOCD_APP_REPO}"
  "    targetRevision: ${ARGOCD_APP_REVISION}"
  "    path: ${ARGOCD_APP_PATH}"
  "  destination:"
  "    server: https://kubernetes.default.svc"
  "    namespace: ${ARGOCD_APP_NAMESPACE}"
  "  syncPolicy:"
  "    automated:"
  "      prune: true"
  "      selfHeal: true"
  "    syncOptions:"
  "      - CreateNamespace=true"
)

if [[ "${IGNORE_INGESTER_REPLICAS}" == "true" ]]; then
  app_lines+=(
    "  ignoreDifferences:"
    "  - group: apps"
    "    kind: Deployment"
    "    name: ingester"
    "    namespace: ${ARGOCD_APP_NAMESPACE}"
    "    jsonPointers:"
    "    - /spec/replicas"
  )
fi

printf_cmd="printf '%s\\n'"
for line in "${app_lines[@]}"; do
  safe_line="${line//\'/\'\"\'\"\'}"
  printf_cmd+=" '${safe_line}'"
done
printf_cmd+=" | sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl apply -f -"

commands=(
  # SSM runs commands via /bin/sh; keep syntax POSIX-compatible.
  "set -eu"
  # Wait for kubectl to be installed by k3s (SSM may run before cloud-init finishes).
  "i=0; while [ \$i -lt 30 ]; do if [ -x /usr/local/bin/kubectl ]; then break; fi; echo \"Waiting for kubectl...\"; sleep 10; i=\$((i+1)); done; if [ ! -x /usr/local/bin/kubectl ]; then echo \"kubectl not found after 300s\"; exit 1; fi"
  # Point kubectl to the k3s kubeconfig on the instance.
  "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
  # Apply the Application manifest.
  "${printf_cmd}"
)

if [[ -n "${WAIT_CRDS}" ]]; then
  wait_list="${WAIT_CRDS//,/ }"
  commands+=(
    "for crd in ${wait_list}; do echo \"Waiting for CRD ${crd}...\"; sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl wait --for=condition=Established crd/${crd} --timeout=300s --request-timeout=5s; done"
  )
fi

ssm_run_commands "${REGION}" "${INSTANCE_ID}" 900 "argocd app ${ARGOCD_APP_NAME}" commands
