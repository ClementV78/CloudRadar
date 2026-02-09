#!/usr/bin/env bash
set -euo pipefail

#
# Redis backup (k3s)
#
# Creates a tar.gz archive of Redis `/data` from the Redis pod and writes a SHA256 file.
# Optionally uploads artifacts to S3.
#
# Intended to run on the k3s server via SSM (or any host with kubectl access to the cluster).
#

usage() {
  cat <<'EOF'
Usage:
  scripts/redis-backup.sh [options]

Options:
  --kubeconfig PATH         Kubeconfig path (default: /etc/rancher/k3s/k3s.yaml)
  --kubectl PATH            Kubectl path (default: auto-detect)
  --namespace NS            Redis namespace (default: data)
  --selector LABELS         Pod selector for Redis (default: app.kubernetes.io/name=redis)
  --out-dir DIR             Output directory on this host (default: /tmp)
  --ts TS                   Timestamp override (default: UTC now, format YYYYMMDDTHHMMSSZ)
  --s3-bucket NAME          Optional S3 bucket to upload to
  --s3-prefix PREFIX        Optional S3 prefix (default: redis-backups/<ts>)
  --region REGION           Optional AWS region for S3 operations (default: use AWS_REGION/AWS_DEFAULT_REGION)
  --no-bgsave               Skip Redis BGSAVE (not recommended)
  -h, --help                Show help

Examples:
  scripts/redis-backup.sh

  scripts/redis-backup.sh --s3-bucket cloudradar-dev-<account-id>-sqlite-backups

Outputs:
  <out-dir>/cloudradar-redis-data-<ts>.tgz
  <out-dir>/cloudradar-redis-data-<ts>.tgz.sha256
EOF
}

KUBECONFIG_PATH="/etc/rancher/k3s/k3s.yaml"
KUBECTL_BIN=""
NS="data"
SELECTOR="app.kubernetes.io/name=redis"
OUT_DIR="/tmp"
TS=""
DO_BGSAVE="true"

S3_BUCKET=""
S3_PREFIX=""
AWS_REGION_OPT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --kubeconfig) KUBECONFIG_PATH="$2"; shift 2 ;;
    --kubectl) KUBECTL_BIN="$2"; shift 2 ;;
    --namespace) NS="$2"; shift 2 ;;
    --selector) SELECTOR="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --ts) TS="$2"; shift 2 ;;
    --s3-bucket) S3_BUCKET="$2"; shift 2 ;;
    --s3-prefix) S3_PREFIX="$2"; shift 2 ;;
    --region) AWS_REGION_OPT="$2"; shift 2 ;;
    --no-bgsave) DO_BGSAVE="false"; shift 1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "${TS}" ]]; then
  TS="$(date -u +%Y%m%dT%H%M%SZ)"
fi

mkdir -p "${OUT_DIR}"
ARCHIVE="${OUT_DIR}/cloudradar-redis-data-${TS}.tgz"
SHA_FILE="${ARCHIVE}.sha256"

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
echo "Archive: ${ARCHIVE}"

if [[ "${DO_BGSAVE}" == "true" ]]; then
  echo "Requesting Redis BGSAVE..."
  "${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- redis-cli BGSAVE
  until "${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- redis-cli INFO persistence | grep -q 'rdb_bgsave_in_progress:0'; do
    sleep 1
  done
else
  echo "Skipping BGSAVE (--no-bgsave)"
fi

echo "Archiving /data from Redis pod..."
rm -f "${ARCHIVE}" "${SHA_FILE}"
"${KUBECTL[@]}" -n "${NS}" exec "${POD}" -c "${CTR}" -- sh -lc 'cd /data && tar -czf - .' > "${ARCHIVE}"

if [[ ! -s "${ARCHIVE}" ]]; then
  echo "ERROR: Archive is empty: ${ARCHIVE}" >&2
  exit 1
fi

sha256sum "${ARCHIVE}" | tee "${SHA_FILE}" >/dev/null
HASH="$(awk '{print $1}' < "${SHA_FILE}")"
echo "${HASH}  $(basename "${ARCHIVE}")" > "${SHA_FILE}"
echo "SHA256: ${HASH}"
ls -lh "${ARCHIVE}" "${SHA_FILE}"

if [[ -n "${S3_BUCKET}" ]]; then
  if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: aws CLI not found. Install awscli or run without --s3-bucket." >&2
    exit 1
  fi

  if [[ -z "${S3_PREFIX}" ]]; then
    S3_PREFIX="redis-backups/${TS}"
  fi

  AWS_REGION_ARGS=()
  if [[ -n "${AWS_REGION_OPT}" ]]; then
    AWS_REGION_ARGS=(--region "${AWS_REGION_OPT}")
  fi

  echo "Uploading to s3://${S3_BUCKET}/${S3_PREFIX}/ ..."
  aws "${AWS_REGION_ARGS[@]}" s3 cp "${ARCHIVE}" "s3://${S3_BUCKET}/${S3_PREFIX}/$(basename "${ARCHIVE}")"
  aws "${AWS_REGION_ARGS[@]}" s3 cp "${SHA_FILE}" "s3://${S3_BUCKET}/${S3_PREFIX}/$(basename "${SHA_FILE}")"
  aws "${AWS_REGION_ARGS[@]}" s3 ls "s3://${S3_BUCKET}/${S3_PREFIX}/"
fi
