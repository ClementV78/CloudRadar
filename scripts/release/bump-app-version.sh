#!/usr/bin/env bash
set -euo pipefail

# Bump VERSION and keep Kubernetes app image tags aligned.
# Usage:
#   scripts/release/bump-app-version.sh            # bump patch (x.y.z -> x.y.z+1)
#   scripts/release/bump-app-version.sh --set 0.2.0

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

set_version="${1:-}"
if [[ "${set_version}" == "--set" ]]; then
  if [[ $# -ne 2 ]]; then
    echo "Usage: $0 [--set <major.minor.patch>]" >&2
    exit 1
  fi
  next_version="${2}"
  if ! [[ "${next_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version format: ${next_version}" >&2
    exit 1
  fi
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--set <major.minor.patch>]" >&2
  exit 1
else
  current_version="$(tr -d '[:space:]' < VERSION)"
  if ! [[ "${current_version}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "Invalid current VERSION: ${current_version}" >&2
    exit 1
  fi
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
  next_version="${major}.${minor}.$((patch + 1))"
fi

printf '%s\n' "${next_version}" > VERSION

services=(
  "ingester:k8s/apps/ingester/deployment.yaml"
  "processor:k8s/apps/processor/deployment.yaml"
  "dashboard:k8s/apps/dashboard/deployment.yaml"
  "health:k8s/apps/health/deployment.yaml"
  "admin-scale:k8s/apps/admin-scale/deployment.yaml"
)

for entry in "${services[@]}"; do
  service="${entry%%:*}"
  file="${entry#*:}"
  if [[ ! -f "${file}" ]]; then
    echo "ERROR: Expected manifest file not found: ${file}" >&2
    exit 1
  fi
  sed -Ei "s#(ghcr\\.io/clementv78/cloudradar/${service}:)[^\"'[:space:]]+#\\1${next_version}#g" "${file}"
done

echo "Updated VERSION and app image tags to ${next_version}"
echo
echo "Verification:"
echo "- VERSION=$(tr -d '[:space:]' < VERSION)"
for entry in "${services[@]}"; do
  service="${entry%%:*}"
  file="${entry#*:}"
  image_ref="$(grep -Eo "ghcr.io/clementv78/cloudradar/${service}:[^\"'[:space:]]+" "${file}" | head -n1 || true)"
  if [[ -z "${image_ref}" ]]; then
    echo "ERROR: Could not find image reference for ${service} in ${file}" >&2
    exit 1
  fi
  image_tag="${image_ref##*:}"
  if [[ "${image_tag}" != "${next_version}" ]]; then
    echo "ERROR: Image tag for ${service} (${image_tag}) does not match VERSION (${next_version})" >&2
    exit 1
  fi
  echo "- ${service}: ${image_ref}"
done

scripts/ci/check-app-version-sync.sh
