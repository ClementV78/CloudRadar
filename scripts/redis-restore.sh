#!/usr/bin/env bash
set -euo pipefail

#
# Redis restore (k3s)
#
# Restores Redis `/data` from a backup tar.gz created by scripts/redis-backup.sh.
# This script avoids fragile globs and avoids extracting under `/data` (to not delete its own source).
#
# Intended to run on the k3s server via SSM (or any host with kubectl access to the cluster).
#

usage() {
  cat <<'EOF'
Usage:
  scripts/redis-restore.sh [options]

Required (one of):
  --archive PATH            Local path to tgz archive to restore
  --s3-uri S3URI            S3 URI to tgz archive (e.g., s3://bucket/prefix/file.tgz)

Optional:
  --sha256 FILE             Local sha256 file (format: "<hash>  <path>") or download from S3 when using --s3-uri
  --kubeconfig PATH         Kubeconfig path (default: /etc/rancher/k3s/k3s.yaml)
  --kubectl PATH            Kubectl path (default: auto-detect)
  --namespace NS            Redis namespace (default: data)
  --selector LABELS         Pod selector for Redis (default: app.kubernetes.io/name=redis)
  --region REGION           Optional AWS region for S3 operations (default: use AWS_REGION/AWS_DEFAULT_REGION)
  --no-restart              Do not restart the Redis pod (not recommended)
  --force                   Required. Acknowledge this will wipe Redis /data before restore.
  -h, --help                Show help

Examples:
  scripts/redis-restore.sh --force --archive /tmp/cloudradar-redis-data-20260207T114140Z.tgz --sha256 /tmp/cloudradar-redis-data-20260207T114140Z.tgz.sha256

  scripts/redis-restore.sh --force --s3-uri s3://cloudradar-dev-<account-id>-sqlite-backups/redis-backups/20260207T114140Z/cloudradar-redis-data-20260207T114140Z.tgz
EOF
}

KUBECONFIG_PATH="/etc/rancher/k3s/k3s.yaml"
KUBECTL_BIN=""
NS="data"
SELECTOR="app.kubernetes.io/name=redis"

ARCHIVE=""
SHA_FILE=""
S3_URI=""
AWS_REGION_OPT=""
DO_RESTART="true"
FORCE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --archive) ARCHIVE="$2"; shift 2 ;;
    --sha256) SHA_FILE="$2"; shift 2 ;;
    --s3-uri) S3_URI="$2"; shift 2 ;;
    --kubeconfig) KUBECONFIG_PATH="$2"; shift 2 ;;
    --kubectl) KUBECTL_BIN="$2"; shift 2 ;;
    --namespace) NS="$2"; shift 2 ;;
    --selector) SELECTOR="$2"; shift 2 ;;
    --region) AWS_REGION_OPT="$2"; shift 2 ;;
    --no-restart) DO_RESTART="false"; shift 1 ;;
    --force) FORCE="true"; shift 1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "${ARCHIVE}" && -z "${S3_URI}" ]]; then
  echo "ERROR: Provide --archive or --s3-uri" >&2
  usage
  exit 2
fi

if [[ "${FORCE}" != "true" ]]; then
  echo "ERROR: Restore is destructive and will wipe Redis /data. Re-run with --force to proceed." >&2
  exit 2
fi

AWS_REGION_ARGS=()
if [[ -n "${AWS_REGION_OPT}" ]]; then
  AWS_REGION_ARGS=(--region "${AWS_REGION_OPT}")
fi

if [[ -n "${S3_URI}" ]]; then
  if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: aws CLI not found. Install awscli or use --archive." >&2
    exit 1
  fi

  ARCHIVE="/tmp/$(basename "${S3_URI}")"
  echo "Downloading archive: ${S3_URI} -> ${ARCHIVE}"
  aws "${AWS_REGION_ARGS[@]}" s3 cp "${S3_URI}" "${ARCHIVE}"

  if [[ -z "${SHA_FILE}" ]]; then
    SHA_FILE="${ARCHIVE}.sha256"
    echo "Downloading sha256: ${S3_URI}.sha256 -> ${SHA_FILE}"
    aws "${AWS_REGION_ARGS[@]}" s3 cp "${S3_URI}.sha256" "${SHA_FILE}"
  fi
fi

if [[ -z "${ARCHIVE}" || ! -f "${ARCHIVE}" || ! -s "${ARCHIVE}" ]]; then
  echo "ERROR: Archive not found or empty: ${ARCHIVE}" >&2
  exit 1
fi

if [[ -n "${SHA_FILE}" ]]; then
  if [[ ! -f "${SHA_FILE}" || ! -s "${SHA_FILE}" ]]; then
    echo "ERROR: SHA256 file not found or empty: ${SHA_FILE}" >&2
    exit 1
  fi
  echo "Verifying SHA256..."
  EXPECTED_HASH="$(awk '{print $1}' < "${SHA_FILE}")"
  ACTUAL_HASH="$(sha256sum "${ARCHIVE}" | awk '{print $1}')"
  if [[ "${EXPECTED_HASH}" != "${ACTUAL_HASH}" ]]; then
    echo "ERROR: SHA256 mismatch" >&2
    echo "  expected: ${EXPECTED_HASH}" >&2
    echo "  actual:   ${ACTUAL_HASH}" >&2
    exit 1
  fi
  echo "SHA256 OK: ${ACTUAL_HASH}"
fi

export KUBECONFIG="${KUBECONFIG_PATH}"

if [[ -z "${KUBECTL_BIN}" ]]; then
  KUBECTL_BIN="$(command -v kubectl || true)"
fi
if [[ -z "${KUBECTL_BIN}" ]]; then
  echo "ERROR: kubectl not found. Provide --kubectl or install kubectl." >&2
  exit 1
fi

KUBECTL=("${KUBECTL_BIN}")
if [[ "$(id -u)" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    KUBECTL=(sudo --preserve-env=KUBECONFIG "${KUBECTL_BIN}")
  else
    echo "ERROR: Not running as root and sudo not found; cannot run kubectl." >&2
    exit 1
  fi
fi

POD="$("${KUBECTL[@]}" -n "${NS}" get pod -l "${SELECTOR}" -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "${POD}" ]]; then
  echo "ERROR: Redis pod not found (ns=${NS} selector=${SELECTOR})" >&2
  exit 1
fi

CTR="$("${KUBECTL[@]}" -n "${NS}" get pod "${POD}" -o jsonpath='{.spec.containers[0].name}')"
if [[ -z "${CTR}" ]]; then
  echo "ERROR: Failed to resolve Redis container name in pod ${POD}" >&2
  exit 1
fi

echo "Redis pod: ${POD} (container: ${CTR})"

echo "Copying archive into pod..."
"${KUBECTL[@]}" -n "${NS}" cp "${ARCHIVE}" "${POD}:/tmp/redis-restore.tgz" -c "${CTR}"

echo "Extracting archive under /tmp (safe temp dir)..."
"${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- sh -lc 'rm -rf /tmp/redis-restore && mkdir -p /tmp/redis-restore && tar -xzf /tmp/redis-restore.tgz -C /tmp/redis-restore'

echo "Restoring to /data..."
"${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- sh -lc 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} + && cp -a /tmp/redis-restore/. /data/'

echo "Cleanup temp files..."
"${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- sh -lc 'rm -rf /tmp/redis-restore /tmp/redis-restore.tgz' || true

if [[ "${DO_RESTART}" == "true" ]]; then
  echo "Restarting Redis pod..."
  "${KUBECTL[@]}" -n "${NS}" delete pod "${POD}"
  "${KUBECTL[@]}" -n "${NS}" rollout status statefulset/redis --timeout=180s

  POD="$("${KUBECTL[@]}" -n "${NS}" get pod -l "${SELECTOR}" -o jsonpath='{.items[0].metadata.name}')"
  CTR="$("${KUBECTL[@]}" -n "${NS}" get pod "${POD}" -o jsonpath='{.spec.containers[0].name}')"
fi

echo "Verification:"
echo "  kubectl -n ${NS} exec ${POD} -c ${CTR} -- redis-cli DBSIZE"
echo "  kubectl -n ${NS} exec ${POD} -c ${CTR} -- redis-cli INFO persistence"
