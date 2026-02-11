#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK_SRC="${ROOT_DIR}/scripts/git-hooks/pre-push"
HOOK_DST="${ROOT_DIR}/.git/hooks/pre-push"

if [[ ! -d "${ROOT_DIR}/.git/hooks" ]]; then
  echo "ERROR: .git/hooks directory not found. Run this from a git checkout." >&2
  exit 1
fi

cp "${HOOK_SRC}" "${HOOK_DST}"
chmod +x "${HOOK_DST}"

echo "Installed pre-push hook: ${HOOK_DST}"
echo "Use SKIP_VERSION_GUARD=1 git push to bypass locally when needed."
