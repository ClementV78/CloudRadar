#!/usr/bin/env bash
set -euo pipefail

# Wait for External Secrets Operator (ESO) to be fully ready on the k3s cluster.
# This is used by CI after bootstrapping the platform ArgoCD app to ensure
# CRDs and controller/webhook are available before applying ExternalSecret resources.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/bootstrap-argocd-common.sh
source "${SCRIPT_DIR}/lib/bootstrap-argocd-common.sh"

usage() {
  cat <<'USAGE'
Usage: bootstrap-eso-ready.sh [--env <env>] [--project <project>] [--region <region>] [<instance-id>] [region]

Example:
  scripts/bootstrap-eso-ready.sh i-0123456789abcdef us-east-1
  scripts/bootstrap-eso-ready.sh --env dev --project cloudradar --region us-east-1

Environment overrides:
  ESO_NAMESPACE=external-secrets
  ESO_WAIT_CRDS=externalsecrets.external-secrets.io,clustersecretstores.external-secrets.io,secretstores.external-secrets.io
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

ESO_NAMESPACE="${ESO_NAMESPACE:-external-secrets}"
ESO_WAIT_CRDS="${ESO_WAIT_CRDS:-externalsecrets.external-secrets.io,clustersecretstores.external-secrets.io,secretstores.external-secrets.io}"

require_cmd aws
require_cmd jq

INSTANCE_ID="$(ensure_instance_id "${REGION}" "${ENVIRONMENT}" "${PROJECT}" "${INSTANCE_ID}")"

# The SSM document runs via /bin/sh, keep the command strings POSIX-only.
wait_list="${ESO_WAIT_CRDS//,/ }"

commands=(
  "set -eu"
  "i=0; while [ \$i -lt 36 ]; do if [ -x /usr/local/bin/kubectl ]; then break; fi; echo \"Waiting for kubectl...\"; sleep 10; i=\$((i+1)); done; if [ ! -x /usr/local/bin/kubectl ]; then echo \"kubectl not found after 360s\"; exit 1; fi"
  "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
  "echo \"Waiting for ESO namespace ${ESO_NAMESPACE}...\""
  "i=0; while [ \$i -lt 60 ]; do if sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get ns \"${ESO_NAMESPACE}\" >/dev/null 2>&1; then break; fi; sleep 5; i=\$((i+1)); done; sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get ns \"${ESO_NAMESPACE}\" >/dev/null 2>&1 || (echo \"ESO namespace not found\"; exit 1)"
  "crd_timeout=600; crd_poll=10; for crd in ${wait_list}; do \
    echo \"Waiting for CRD \${crd}...\"; \
    elapsed=0; \
    while ! sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get crd \"\${crd}\" >/dev/null 2>&1; do \
      if [ \${elapsed} -ge \${crd_timeout} ]; then echo \"CRD \${crd} not found after \${crd_timeout}s\"; exit 1; fi; \
      sleep \${crd_poll}; elapsed=\$((elapsed + crd_poll)); \
    done; \
    while :; do \
      if [ \${elapsed} -ge \${crd_timeout} ]; then \
        echo \"CRD \${crd} not Established=True after \${crd_timeout}s\"; \
        sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get crd \"\${crd}\" -o yaml | sed -n '1,120p' || true; \
        exit 1; \
      fi; \
      # Don't grep the raw JSON: field order is not guaranteed, and this can create false negatives.
      # jsonpath output is stable for this use-case and doesn't require jq on the instance.
      if sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl get crd \"\${crd}\" -o jsonpath='{range .status.conditions[*]}{.type}={.status}{\"\\n\"}{end}' 2>/dev/null | tr -d '\\r' | grep -q '^Established=True$'; then \
        echo \"CRD \${crd} Established=True\"; \
        break; \
      fi; \
      sleep \${crd_poll}; elapsed=\$((elapsed + crd_poll)); \
    done; \
  done"
  "echo \"Waiting for ESO deployments to be Available...\""
  "for d in external-secrets-operator external-secrets-operator-webhook external-secrets-operator-cert-controller; do \
    echo \"rollout status deploy/\${d}\"; \
    sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n \"${ESO_NAMESPACE}\" rollout status \"deploy/\${d}\" --timeout=300s || { \
      echo \"--- pods\"; sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n \"${ESO_NAMESPACE}\" get pods -o wide || true; \
      echo \"--- describe deploy/\${d}\"; sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n \"${ESO_NAMESPACE}\" describe \"deploy/\${d}\" | sed -n '1,220p' || true; \
      exit 1; \
    }; \
  done"
  "echo \"ESO is ready.\""
)

ssm_run_commands "${REGION}" "${INSTANCE_ID}" 1200 "eso ready" commands
