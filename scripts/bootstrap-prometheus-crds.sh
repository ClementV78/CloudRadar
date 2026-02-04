#!/usr/bin/env bash
set -euo pipefail

# Apply Prometheus Operator CRDs before ArgoCD (server-side apply).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/bootstrap-argocd-common.sh
source "${SCRIPT_DIR}/lib/bootstrap-argocd-common.sh"

usage() {
  cat <<'USAGE'
Usage: bootstrap-prometheus-crds.sh [--env <env>] [--project <project>] [--region <region>] [<instance-id>] [region]

Example:
  scripts/bootstrap-prometheus-crds.sh i-0123456789abcdef us-east-1
  scripts/bootstrap-prometheus-crds.sh --env dev --project cloudradar --region us-east-1

Environment overrides:
  PROMETHEUS_CRD_REPO=ClementV78/CloudRadar
  PROMETHEUS_CRD_REVISION=main
  PROMETHEUS_CRD_DIR=k8s/platform/crds/prometheus
  PROMETHEUS_CRD_TIMEOUT=300s
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

CRD_REPO="${PROMETHEUS_CRD_REPO:-${GITHUB_REPOSITORY:-ClementV78/CloudRadar}}"
CRD_REVISION="${PROMETHEUS_CRD_REVISION:-${GITHUB_SHA:-main}}"
CRD_DIR="${PROMETHEUS_CRD_DIR:-k8s/platform/crds/prometheus}"
CRD_TIMEOUT="${PROMETHEUS_CRD_TIMEOUT:-300s}"

require_cmd aws
require_cmd jq

CRD_PATH="${REPO_ROOT}/${CRD_DIR}"
if [[ ! -d "${CRD_PATH}" ]]; then
  echo "Missing CRD directory: ${CRD_PATH}" >&2
  exit 1
fi

mapfile -t CRD_FILES < <(ls -1 "${CRD_PATH}"/*.yaml 2>/dev/null | xargs -n1 basename)
if [[ "${#CRD_FILES[@]}" -eq 0 ]]; then
  echo "No CRD files found in ${CRD_PATH}" >&2
  exit 1
fi

CRD_URLS=()
for crd_file in "${CRD_FILES[@]}"; do
  CRD_URLS+=("https://raw.githubusercontent.com/${CRD_REPO}/${CRD_REVISION}/${CRD_DIR}/${crd_file}")
done

URLS_ESCAPED=$(printf "%q " "${CRD_URLS[@]}")

INSTANCE_ID="$(ensure_instance_id "${REGION}" "${ENVIRONMENT}" "${PROJECT}" "${INSTANCE_ID}")"

commands=(
  "set -eu"
  "i=0; while [ \$i -lt 30 ]; do if [ -x /usr/local/bin/kubectl ]; then break; fi; echo \"Waiting for kubectl...\"; sleep 10; i=\$((i+1)); done; if [ ! -x /usr/local/bin/kubectl ]; then echo \"kubectl not found after 300s\"; exit 1; fi"
  "command -v curl >/dev/null 2>&1 || (command -v dnf >/dev/null 2>&1 && sudo dnf install -y curl) || (command -v yum >/dev/null 2>&1 && sudo yum install -y curl)"
  "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
  "echo \"Applying Prometheus CRDs from ${CRD_REPO}@${CRD_REVISION}\""
  "for url in ${URLS_ESCAPED}; do echo \"Applying CRD: \${url}\"; curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors \"\${url}\" | sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl apply --server-side --force-conflicts --validate=false --request-timeout=${CRD_TIMEOUT} -f -; done"
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get crd | grep -E 'prometheuses|alertmanagers|prometheusagents|thanosrulers|scrapeconfigs' || true"
)

ssm_run_commands "${REGION}" "${INSTANCE_ID}" 1200 "prometheus crds" commands
