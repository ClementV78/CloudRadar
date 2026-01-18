#!/usr/bin/env bash
set -euo pipefail

# Bootstrap ArgoCD on the k3s server via SSM, then create the root GitOps Application.
# Steps: resolve target instance, install Helm + ArgoCD, wait for readiness, apply the Application manifest.

usage() {
  cat <<'USAGE'
Usage: bootstrap-argocd.sh [--env <env>] [--project <project>] <instance-id> [region] [argocd-chart-version]

Example:
  scripts/bootstrap-argocd.sh i-0123456789abcdef us-east-1
  scripts/bootstrap-argocd.sh --env dev --project cloudradar us-east-1

Environment overrides:
  ARGOCD_NAMESPACE=argocd
  ARGOCD_APP_NAME=cloudradar
  ARGOCD_APP_NAMESPACE=cloudradar
  ARGOCD_APP_REPO=https://github.com/ClementV78/CloudRadar.git
  ARGOCD_APP_PATH=k8s/apps
  ARGOCD_APP_REVISION=main
  ARGOCD_CHART_VERSION=9.3.4
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
ARGOCD_CHART_VERSION="${2:-${ARGOCD_CHART_VERSION:-9.3.4}}"
ARGOCD_NAMESPACE="${ARGOCD_NAMESPACE:-argocd}"
ARGOCD_APP_NAME="${ARGOCD_APP_NAME:-cloudradar}"
ARGOCD_APP_NAMESPACE="${ARGOCD_APP_NAMESPACE:-cloudradar}"
ARGOCD_APP_REPO="${ARGOCD_APP_REPO:-https://github.com/ClementV78/CloudRadar.git}"
ARGOCD_APP_PATH="${ARGOCD_APP_PATH:-k8s/apps}"
ARGOCD_APP_REVISION="${ARGOCD_APP_REVISION:-main}"
HELM_VERSION_FLAG=""

if [[ -n "${ARGOCD_CHART_VERSION}" ]]; then
  HELM_VERSION_FLAG="--version ${ARGOCD_CHART_VERSION}"
fi

if ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI is required." >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to build SSM parameters." >&2
  exit 1
fi

if [[ -z "${INSTANCE_ID}" ]]; then
  # Resolve the k3s server instance via tags when an explicit ID is not provided.
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
  # Summary: Install ArgoCD via Helm and create the GitOps Application manifest.
  # SSM runs commands via /bin/sh; keep syntax POSIX-compatible.
  "set -eu"
  # Wait for kubectl to be installed by k3s (SSM may run before cloud-init finishes).
  "i=0; while [ \\$i -lt 30 ]; do if [ -x /usr/local/bin/kubectl ]; then break; fi; echo \"Waiting for kubectl...\"; sleep 10; i=\\$((i+1)); done; if [ ! -x /usr/local/bin/kubectl ]; then echo \"kubectl not found after 300s\"; exit 1; fi"
  # Point kubectl/helm to the k3s kubeconfig on the instance.
  "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
  # Install Helm if missing (k3s AMIs might not include it).
  "command -v helm >/dev/null 2>&1 || curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | sudo bash"
  # Add/update Argo Helm repo so the chart is available.
  "sudo --preserve-env=KUBECONFIG helm repo add argo https://argoproj.github.io/argo-helm --force-update"
  "sudo --preserve-env=KUBECONFIG helm repo update"
  # Install or upgrade ArgoCD into its namespace.
  "sudo --preserve-env=KUBECONFIG helm upgrade --install argocd argo/argo-cd --namespace ${ARGOCD_NAMESPACE} --create-namespace ${HELM_VERSION_FLAG}"
  # Wait for CRDs and ArgoCD server to be ready before creating the Application.
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl wait --for=condition=Established crd/applications.argoproj.io --timeout=120s"
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n ${ARGOCD_NAMESPACE} wait --for=condition=Available deployment/argocd-server --timeout=300s"
  "sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl -n ${ARGOCD_NAMESPACE} get pods -o wide"
  # Apply the root Application for GitOps sync (auto-sync enabled).
  "printf '%s\n' \"apiVersion: argoproj.io/v1alpha1\" \"kind: Application\" \"metadata:\" \"  name: ${ARGOCD_APP_NAME}\" \"  namespace: ${ARGOCD_NAMESPACE}\" \"spec:\" \"  project: default\" \"  source:\" \"    repoURL: ${ARGOCD_APP_REPO}\" \"    targetRevision: ${ARGOCD_APP_REVISION}\" \"    path: ${ARGOCD_APP_PATH}\" \"  destination:\" \"    server: https://kubernetes.default.svc\" \"    namespace: ${ARGOCD_APP_NAMESPACE}\" \"  syncPolicy:\" \"    automated:\" \"      prune: true\" \"      selfHeal: true\" \"    syncOptions:\" \"      - CreateNamespace=true\" | sudo --preserve-env=KUBECONFIG /usr/local/bin/kubectl apply -f -"
)

 # Encode commands for SSM RunShellScript.
params_json="$(printf "%s\n" "${commands[@]}" | jq -Rn '{commands: [inputs]}')"

command_id="$(aws ssm send-command \
  --region "${REGION}" \
  --instance-ids "${INSTANCE_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "${params_json}" \
  --query "Command.CommandId" \
  --output text)"

echo "::notice title=SSM::argocd bootstrap command_id=${command_id}"

aws ssm wait command-executed \
  --region "${REGION}" \
  --command-id "${command_id}" \
  --instance-id "${INSTANCE_ID}"

aws ssm get-command-invocation \
  --region "${REGION}" \
  --command-id "${command_id}" \
  --instance-id "${INSTANCE_ID}"
