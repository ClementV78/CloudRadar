#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper for the ArgoCD bootstrap flow.
# Prefer using bootstrap-argocd-install.sh + bootstrap-argocd-app.sh directly.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cat <<'NOTICE' >&2
NOTICE: scripts/bootstrap-argocd.sh is now a wrapper.
- install: scripts/bootstrap-argocd-install.sh
- apps:    scripts/bootstrap-argocd-app.sh (platform/apps)
NOTICE

"${SCRIPT_DIR}/bootstrap-argocd-install.sh" "$@"

WAIT_CRDS="${WAIT_CRDS:-externalsecrets.external-secrets.io,clustersecretstores.external-secrets.io,secretstores.external-secrets.io}" \
ARGOCD_APP_NAME="${ARGOCD_PLATFORM_APP_NAME:-cloudradar-platform}" \
ARGOCD_APP_NAMESPACE="${ARGOCD_PLATFORM_APP_NAMESPACE:-argocd}" \
ARGOCD_APP_PATH="${ARGOCD_PLATFORM_APP_PATH:-k8s/platform}" \
"${SCRIPT_DIR}/bootstrap-argocd-app.sh" "$@"

IGNORE_INGESTER_REPLICAS=true \
ARGOCD_APP_NAME="${ARGOCD_APPS_APP_NAME:-cloudradar}" \
ARGOCD_APP_NAMESPACE="${ARGOCD_APPS_APP_NAMESPACE:-cloudradar}" \
ARGOCD_APP_PATH="${ARGOCD_APPS_APP_PATH:-k8s/apps}" \
"${SCRIPT_DIR}/bootstrap-argocd-app.sh" "$@"
