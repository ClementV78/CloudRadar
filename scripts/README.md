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

## ci/find-orphans.sh

Runs state-vs-tagged orphan checks used by infra CI before deploy and after destroy.

See detailed documentation:
- `scripts/ci/find-orphans.md`

## build-aircraft-sqlite.py

Builds a lightweight aircraft reference database (`aircraft.db`) from a large CSV dataset.
Optionally merges a secondary NDJSON dataset to:
- add missing ICAO24 rows
- enrich existing rows with `military_hint`, `year_built`, and `owner_operator`
- fill empty metadata fields when available (`registration`, `manufacturer_name`, `model`, `typecode`, `icao_aircraft_class`)

Data sources:
- Primary CSV dataset: OpenSky Network open data aircraft database export.
- Optional NDJSON enrichment dataset: ADSB Exchange (`adsbexchange.com`), "World's largest source of unfiltered flight data".

This is intended for local/offline use. Do not commit the raw CSV nor the generated SQLite file.

Usage:

```bash
python3 scripts/build-aircraft-sqlite.py \
  --input .local/.tmp/aircraft-database-complete-2025-08.csv \
  --output .local/.tmp/aircraft.db \
  --drop-existing
```

Usage with merge:

```bash
python3 scripts/build-aircraft-sqlite.py \
  --input .local/.tmp/aircraft-database-complete-2025-08.csv \
  --output .local/.tmp/aircraft.db \
  --merge-basic-json .local/.tmp/adsb-reference.ndjson \
  --drop-existing
```
