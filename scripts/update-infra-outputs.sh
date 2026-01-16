#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="dev"
FORCE_INIT="false"

usage() {
  cat <<'USAGE'
Usage: update-infra-outputs.sh [env] [--init]

Options:
  --init    Run terraform init with backend.hcl before reading outputs.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --init)
      FORCE_INIT="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      ENV_NAME="$1"
      shift
      ;;
  esac
done
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="${ROOT_DIR}/infra/aws/live/${ENV_NAME}"
OUT_FILE="${ROOT_DIR}/docs/runbooks/infra-outputs.md"

if [[ ! -d "$ENV_DIR" ]]; then
  echo "Unknown environment: $ENV_NAME (expected infra/aws/live/<env>)" >&2
  exit 1
fi

if ! command -v terraform >/dev/null 2>&1; then
  echo "terraform not found in PATH" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found in PATH" >&2
  exit 1
fi

backend_config="${ENV_DIR}/backend.hcl"
needs_init="false"
if [[ "$FORCE_INIT" == "true" ]]; then
  needs_init="true"
elif [[ ! -d "${ENV_DIR}/.terraform" ]]; then
  needs_init="true"
fi

if [[ "$needs_init" == "true" ]]; then
  if [[ ! -f "$backend_config" ]]; then
    echo "backend.hcl not found at ${backend_config}; cannot run terraform init." >&2
    exit 1
  fi

  echo "Warning: terraform init will reconfigure the backend for ${ENV_DIR}." >&2
  echo "This can affect local Terraform commands in that directory." >&2
  read -r -p "Type 'yes' to proceed with terraform init: " confirm
  if [[ "$confirm" != "yes" ]]; then
    echo "Aborted." >&2
    exit 1
  fi

  terraform -chdir="$ENV_DIR" init -input=false -reconfigure -backend-config="$backend_config"
fi
tf_err="$(mktemp)"
tf_out="$(mktemp)"
terraform -chdir="$ENV_DIR" output -json >"$tf_out" 2>"$tf_err"
tf_status=$?
if [[ $tf_status -ne 0 ]]; then
  echo "terraform output failed in ${ENV_DIR}:" >&2
  cat "$tf_err" >&2
  rm -f "$tf_err" "$tf_out"
  exit $tf_status
fi
rm -f "$tf_err"

if [[ -z "$(tr -d '[:space:]' < "$tf_out")" ]]; then
  echo "No outputs found (empty terraform output). Did you run init/apply in ${ENV_DIR}?" >&2
  printf '{}' >"$tf_out"
fi

python3 - "$ENV_NAME" "$OUT_FILE" "$tf_out" <<'PY'
import datetime
import json
import sys

env_name = sys.argv[1]
out_file = sys.argv[2]
tf_out = sys.argv[3]
try:
  with open(tf_out, "r", encoding="utf-8") as handle:
    data = json.load(handle)
except json.JSONDecodeError as exc:
  sys.stderr.write("Failed to parse terraform output JSON. Raw output:\n")
  with open(tf_out, "r", encoding="utf-8") as handle:
    sys.stderr.write(handle.read() + "\n")
  sys.stderr.write(f"JSON error: {exc}\n")
  raise SystemExit(1)

lines = []
lines.append(f"# Infra Outputs ({env_name})")
lines.append("")
timestamp = datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%d %H:%M UTC")
lines.append(f"_Generated: {timestamp}_")
lines.append("")
lines.append("> This file is generated locally and is intentionally not committed.")
lines.append("")

if not data:
  lines.append("_No outputs found._")
else:
  lines.append("| Output | Type | Value |")
  lines.append("| --- | --- | --- |")
  for key in sorted(data.keys()):
    value = json.dumps(data[key].get("value"), ensure_ascii=True)
    value_type = json.dumps(data[key].get("type"), ensure_ascii=True)
    lines.append(f"| `{key}` | `{value_type}` | `{value}` |")

with open(out_file, "w", encoding="utf-8") as handle:
  handle.write("\n".join(lines) + "\n")
PY

rm -f "$tf_out"
if command -v stat >/dev/null 2>&1; then
  file_meta="$(stat -c "last modified: %y | size: %s bytes" "$OUT_FILE" 2>/dev/null || true)"
fi
echo "Wrote ${OUT_FILE}"
if [[ -n "${file_meta:-}" ]]; then
  echo "Output file ${file_meta}"
fi
