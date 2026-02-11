#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK_SRC="${ROOT_DIR}/scripts/git-hooks/pre-push"
HOOK_DST="${ROOT_DIR}/.git/hooks/pre-push"
FORCE=0

if [[ "${1:-}" == "--force" ]]; then
  FORCE=1
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--force]" >&2
  exit 1
fi

if [[ ! -d "${ROOT_DIR}/.git/hooks" ]]; then
  echo "ERROR: .git/hooks directory not found. Run this from a git checkout." >&2
  exit 1
fi

if [[ -f "${HOOK_DST}" && "${FORCE}" -ne 1 ]]; then
  timestamp="$(date +%Y%m%d%H%M%S)"
  backup_path="${HOOK_DST}.bak.${timestamp}"
  cp "${HOOK_DST}" "${backup_path}"
  echo "Existing pre-push hook backed up to: ${backup_path}"
fi

cp "${HOOK_SRC}" "${HOOK_DST}"
chmod +x "${HOOK_DST}"

echo "Installed pre-push hook: ${HOOK_DST}"
echo "Use SKIP_VERSION_GUARD=1 git push to bypass locally when needed."
