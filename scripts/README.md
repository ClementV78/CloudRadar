# Scripts

Utility scripts for local workflows and bootstrap tasks.

## get-aws-kubeconfig.sh

Fetches the k3s kubeconfig via SSM, rewrites the API endpoint for a local
port-forward tunnel, and runs `kubectl get nodes`.

Usage:

```bash
source scripts/get-aws-kubeconfig.sh <instance-id> [local-port]
```

Notes:
- Default local port is `16443`.
- Override kubeconfig path with `KUBECONFIG_PATH=/tmp/k3s-aws.yaml`.

## bootstrap-prometheus-crds.sh

Applies Prometheus Operator CRDs on the k3s server via SSM (server-side apply).
Used before ArgoCD to ensure CRDs exist.

Usage:

```bash
scripts/bootstrap-prometheus-crds.sh <instance-id> us-east-1
```

Notes:
- Overrides: `PROMETHEUS_CRD_REPO`, `PROMETHEUS_CRD_REVISION`, `PROMETHEUS_CRD_DIR`, `PROMETHEUS_CRD_TIMEOUT`.

## build-aircraft-sqlite.py

Builds a lightweight aircraft reference database (`aircraft.db`) from a large CSV dataset.

This is intended for local/offline use. Do not commit the raw CSV nor the generated SQLite file.

Usage:

```bash
python3 scripts/build-aircraft-sqlite.py \
  --input .local/.tmp/aircraft-database-complete-2025-08.csv \
  --output .local/.tmp/aircraft.db \
  --drop-existing
```
