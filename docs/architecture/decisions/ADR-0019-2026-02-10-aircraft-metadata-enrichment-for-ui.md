# ADR-0019: Aircraft Metadata Enrichment for UI (On-the-Fly + In-Process Cache)

Date: 2026-02-10

## Status
Accepted

## Context
CloudRadar needs to show live aircraft positions and aircraft metadata in the future dashboard API / frontend UI (issue #129).

We already distribute an aircraft reference database as a versioned SQLite artifact stored in S3 and downloaded locally in the cluster (see ADR-0018).

We also want application KPIs in Grafana/Prometheus, but Prometheus is not suitable as a golden source for per-aircraft business objects due to storage semantics and label cardinality.

## Decision
1. **Live telemetry stays in Redis** as the realtime cache:
   - `cloudradar:aircraft:last`
   - `cloudradar:aircraft:track:<icao24>`
   - `cloudradar:aircraft:in_bbox`

2. **Aircraft metadata enrichment for the UI/API is performed on the fly**:
   - The dashboard API (or whichever service serves UI reads) reads positions from Redis.
   - It performs local SQLite lookups by `icao24` to fetch metadata fields (country, manufacturer, model, registration, typecode, etc.).

3. **Metadata lookups use an in-process cache** (LRU + TTL) inside the UI/API service:
   - Cache warms naturally based on UI access patterns.
   - Cache misses fall back to local SQLite.
   - No shared cache layer is required for the MVP.

4. **Prometheus/Grafana is used for aggregated KPIs only** (low cardinality):
   - Queue depth, throughput, processing latency
   - Category counters (e.g., events by aircraft category)
   - Avoid per-aircraft series labels (`icao24`, callsign, registration, etc.).

## Consequences
### Positive
- Avoids write amplification and Redis bloat (metadata is quasi-static and should not be rewritten each telemetry tick).
- Keeps responsibilities clear:
  - Redis = realtime telemetry cache
  - SQLite = reference dataset
  - API = assembly layer for UI
- Low cost and low operational complexity (no new managed databases).

### Negative
- UI/API must perform an extra lookup step (Redis + SQLite), mitigated by the in-process cache.
- Per-pod cache warmup after restarts (acceptable for MVP).

## Alternatives Considered
1. **Store enriched metadata directly in Redis**
   - Pros: simpler reads, fewer lookups
   - Cons: write amplification, higher Redis memory usage, duplicated data, harder to evolve the enrichment model.

2. **Use Redis as a shared metadata cache**
   - Pros: shared cache across replicas
   - Cons: extra network hop and operational complexity while the reference DB is already local.
   - Revisit only if we scale replicas and observe SQLite pressure.

3. **Use Prometheus as the metadata source**
   - Rejected: Prometheus is for time-series KPIs, not per-aircraft metadata objects.

## Links
- Issue: #383 (aircraft metadata enrichment strategy)
- Depends-on: issue #129 (dashboard API)
- ADR-0018: Reference Aircraft Database Distribution (S3 + Local SQLite)

