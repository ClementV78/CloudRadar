#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

expected_version="$(tr -d '[:space:]' < VERSION)"
if ! [[ "${expected_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: VERSION is invalid: ${expected_version}" >&2
  exit 1
fi

services=(
  "ingester:k8s/apps/ingester/deployment.yaml"
  "processor:k8s/apps/processor/deployment.yaml"
  "frontend:k8s/apps/frontend/deployment.yaml"
  "dashboard:k8s/apps/dashboard/deployment.yaml"
  "health:k8s/apps/health/deployment.yaml"
  "admin-scale:k8s/apps/admin-scale/deployment.yaml"
)

errors=0
for entry in "${services[@]}"; do
  service="${entry%%:*}"
  file="${entry#*:}"
  if [[ ! -f "${file}" ]]; then
    echo "ERROR: Expected manifest file not found: ${file}" >&2
    errors=1
    continue
  fi
  image_ref="$(grep -Eo "ghcr.io/clementv78/cloudradar/${service}:[^\"'[:space:]]+" "${file}" | head -n1 || true)"
  actual_version="${image_ref##*:}"
  if [[ -z "${image_ref}" ]]; then
    echo "ERROR: ${file} does not contain an image for service '${service}'." >&2
    errors=1
    continue
  fi
  if [[ "${actual_version}" != "${expected_version}" ]]; then
    echo "ERROR: ${service} image tag is '${actual_version}' in ${file}, expected '${expected_version}' from VERSION." >&2
    errors=1
  fi
done

if [[ "${errors}" -ne 0 ]]; then
  echo >&2
  echo "Fix:" >&2
  echo "  scripts/release/bump-app-version.sh --set ${expected_version}" >&2
  exit 1
fi

echo "OK: app image tags are aligned with VERSION=${expected_version}."
