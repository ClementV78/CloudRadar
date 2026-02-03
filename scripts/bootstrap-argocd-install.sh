#!/usr/bin/env bash
set -euo pipefail

# Install/upgrade ArgoCD on the k3s server via SSM.
# Steps: resolve target instance, install Helm + ArgoCD, wait for readiness.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/bootstrap-argocd-common.sh
source "${SCRIPT_DIR}/lib/bootstrap-argocd-common.sh"

usage() {
  cat <<'USAGE'
Usage: bootstrap-argocd-install.sh [--env <env>] [--project <project>] [--region <region>] [<instance-id>] [argocd-chart-version]

Example:
  scripts/bootstrap-argocd-install.sh i-0123456789abcdef us-east-1
  scripts/bootstrap-argocd-install.sh --env dev --project cloudradar --region us-east-1

Environment overrides:
  ARGOCD_NAMESPACE=argocd
  ARGOCD_CHART_VERSION=9.3.4
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

ARGOCD_CHART_VERSION="${1:-${ARGOCD_CHART_VERSION:-9.3.4}}"
ARGOCD_NAMESPACE="${ARGOCD_NAMESPACE:-argocd}"
ARGOCD_VALUES_FILE="${SCRIPT_DIR}/argocd-values.yaml"
ARGOCD_VALUES_CONTENT=""
HELM_VERSION_FLAG=""

if [[ -n "${ARGOCD_CHART_VERSION}" ]]; then
  HELM_VERSION_FLAG="--version ${ARGOCD_CHART_VERSION}"
fi

require_cmd aws
require_cmd jq

if [[ -f "${ARGOCD_VALUES_FILE}" ]]; then
  ARGOCD_VALUES_CONTENT="$(cat "${ARGOCD_VALUES_FILE}")"
else
  echo "Missing ${ARGOCD_VALUES_FILE}. Run from the repo root." >&2
  exit 1
fi

INSTANCE_ID="$(ensure_instance_id "${REGION}" "${ENVIRONMENT}" "${PROJECT}" "${INSTANCE_ID}")"

commands=(
  # SSM runs commands via /bin/sh; keep syntax POSIX-compatible.
  "set -eu"
  # Wait for kubectl to be installed by k3s (SSM may run before cloud-init finishes).
  "i=0; while [ \$i -lt 30 ]; do if [ -x /usr/local/bin/kubectl ]; then break; fi; echo \"Waiting for kubectl...\"; sleep 10; i=\$((i+1)); done; if [ ! -x /usr/local/bin/kubectl ]; then echo \"kubectl not found after 300s\"; exit 1; fi"
  # Write repo-server tuning values to a local file on the instance (SSM doesn't have the repo).
  "cat <<'EOF' >/tmp/argocd-values.yaml
${ARGOCD_VALUES_CONTENT}
EOF"
  # Point kubectl/helm to the k3s kubeconfig on the instance.
  "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
  # Install Helm if missing (k3s AMIs might not include it).
  "command -v helm >/dev/null 2>&1 || curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | sudo bash"
  # Add/update Argo Helm repo so the chart is available.
  "sudo --preserve-env=KUBECONFIG helm repo add argo https://argoproj.github.io/argo-helm --force-update"
  "sudo --preserve-env=KUBECONFIG helm repo update"
  # Install or upgrade ArgoCD into its namespace.
  "sudo --preserve-env=KUBECONFIG helm upgrade --install argocd argo/argo-cd --namespace ${ARGOCD_NAMESPACE} --create-namespace ${HELM_VERSION_FLAG} --values /tmp/argocd-values.yaml --set-json 'global.nodeSelector={\"node-role.kubernetes.io/control-plane\":\"true\"}' --set-json 'global.tolerations=[{\"key\":\"node-role.kubernetes.io/control-plane\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]'"
  # Wait for CRDs and ArgoCD server to be ready.
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl wait --for=condition=Established crd/applications.argoproj.io --timeout=120s --request-timeout=5s"
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n ${ARGOCD_NAMESPACE} wait --for=condition=Available deployment/argocd-server --timeout=300s --request-timeout=5s"
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n ${ARGOCD_NAMESPACE} get pods -o wide --request-timeout=5s"
)

ssm_run_commands "${REGION}" "${INSTANCE_ID}" 1200 "argocd install" commands
