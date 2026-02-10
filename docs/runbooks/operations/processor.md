# Processor (Redis aggregates)

Purpose: consume ingester events from Redis and build in-memory aggregates for the UI.

## Prerequisites
- k3s cluster is running.
- Redis buffer is deployed (`docs/runbooks/redis.md`).
- Ingester is producing events (`docs/runbooks/ingester.md`).

## Deploy
```bash
kubectl apply -k k8s/apps/processor
```

## Verify
```bash
kubectl -n cloudradar get deploy processor
kubectl -n cloudradar get pods -l app.kubernetes.io/name=processor
kubectl -n cloudradar port-forward deploy/processor 8080:8080
curl -s http://localhost:8080/healthz
curl -s http://localhost:8080/metrics/prometheus | head -n 20
```

## Redis aggregates (v1)
- Last position per aircraft: `cloudradar:aircraft:last` (hash, key = `icao24`).
- Short track per aircraft: `cloudradar:aircraft:track:<icao24>` (list, newest first).
- Aircraft inside bbox: `cloudradar:aircraft:in_bbox` (set).

## Aircraft reference DB (optional)
The processor can optionally enrich events using a local SQLite reference database built from an external ICAO24 dataset.

### Build the SQLite artifact (local)
```bash
python3 scripts/build-aircraft-sqlite.py \
  --input /path/to/aircraft-database.csv \
  --output /tmp/aircraft.db \
  --drop-existing
```

### Upload the artifact (S3)
Upload the generated `aircraft.db` to your reference bucket (versioned path recommended), for example:
`s3://<reference-bucket>/aircraft-db/<version>/aircraft.db`

### Enable in Kubernetes
In `k8s/apps/processor/deployment.yaml`:
- Set initContainer env:
  - `AIRCRAFT_DB_S3_URI` to the S3 URI
  - optionally `AIRCRAFT_DB_SHA256` for integrity verification
- Set processor env:
  - `PROCESSOR_AIRCRAFT_DB_ENABLED=true`
  - `PROCESSOR_AIRCRAFT_DB_PATH=/refdata/aircraft.db`

Then deploy:
```bash
kubectl apply -k k8s/apps/processor
kubectl -n cloudradar rollout status deploy/processor
```

## Notes
- The processor uses a blocking pop with a short timeout to minimize CPU when the queue is empty.
- Bbox limits are configured via env vars (`PROCESSOR_LAT_MIN`, `PROCESSOR_LAT_MAX`, `PROCESSOR_LON_MIN`, `PROCESSOR_LON_MAX`).
- Metrics exposed via Actuator: `/metrics/prometheus` and `/healthz`.
- Persistence to SQLite is tracked in #165 and is out of scope for v1.
- Track length defaults to 180 points (roughly 30 minutes at 10s refresh).
