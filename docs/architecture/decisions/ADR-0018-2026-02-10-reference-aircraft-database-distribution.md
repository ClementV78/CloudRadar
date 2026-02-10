# ADR-0018: Reference Aircraft Database Distribution (S3 + Local SQLite)

Date: 2026-02-10
Status: Proposed

## Context

CloudRadar ingests flight position events (OpenSky) that include `icao24` identifiers. We want to enrich telemetry and dashboards with aircraft metadata (e.g., `country`, `icaoAircraftClass`, `manufacturer`, `model`, `registration`, `typecode`) and derive low-cardinality categories for interview-ready Grafana dashboards.

We currently have a large third-party dataset (CSV, ~100MB, ~600k rows). Committing it to the repository is undesirable (repo bloat, review noise), and storing it as a Kubernetes ConfigMap is not viable (size limits and apply/annotation constraints).

Requirements:
- Low ongoing cost (FinOps): avoid always-on managed databases for static reference data.
- Fast lookups by `icao24` at runtime (ingester/processor path).
- Versioned, reproducible reference data with integrity checks.
- Works with GitOps/Kubernetes deployments.

## Decision

We will distribute the aircraft reference dataset via **Amazon S3** as a versioned artifact and use a **local read-only SQLite database** inside the application pod for lookups.

### Data format

The source CSV will be transformed into a SQLite file (e.g., `aircraft.db`) containing only the fields CloudRadar needs:
- `icao24` (PRIMARY KEY)
- `country`
- `categoryDescription`
- `icaoAircraftClass`
- `manufacturerIcao`, `manufacturerName`
- `model`
- `registration`
- `typecode`

The table will be indexed at least on `icao24`. Additional indexes are optional and should be justified by query patterns.

### Distribution and integrity

- The artifact is uploaded to S3 under a versioned path, for example:
  - `s3://<reference-bucket>/aircraft-db/<version>/aircraft.db`
- Deployments pin the artifact by:
  - `AIRCRAFT_DB_VERSION` (path selector)
  - `AIRCRAFT_DB_SHA256` (integrity check)

### Runtime consumption in Kubernetes

The pod will download the SQLite artifact at startup into an `emptyDir` volume using an initContainer (or equivalent startup step):
- InitContainer downloads from S3 and verifies SHA256.
- Application opens SQLite in **read-only** mode from the mounted path.
- Application may keep an in-memory cache (LRU) for hot `icao24` lookups to reduce disk I/O.

### Dashboard-friendly categorization

For Grafana "top N categories" panels, we will derive a low-cardinality category:
- Prefer `categoryDescription` when present.
- Fallback to `icaoAircraftClass` when `categoryDescription` is missing/empty.

High-cardinality dimensions (notably `typecode`) must be used carefully in Prometheus (bounded Top-N exports only, if ever).

## Alternatives Considered

### 1) Commit CSV in repo
- Pros: simplest distribution.
- Cons: repo bloat, slow clones, noisy diffs, not GitOps-friendly for large binaries.

### 2) ConfigMap (or Secret) with CSV/JSON
- Pros: fully in-cluster.
- Cons: size limits and apply issues; not appropriate for 100MB+ artifacts.

### 3) Bake the dataset into the Docker image
- Pros: simplest runtime (no network fetch).
- Cons: large images, slow pulls, requires rebuild/redeploy to update the dataset, couples data updates to app releases.

### 4) Managed database (DynamoDB / RDS)
- Pros: clean query model and central source of truth.
- Cons: ongoing cost and operational overhead for static reference data; not aligned with low-cost MVP goals.

### 5) Query S3 directly (Athena / S3 Select)
- Pros: no extra service to run.
- Cons: per-query cost and latency; complexity; poor fit for realtime enrichment.

## Consequences

### Positive
- Near-zero steady-state cost (S3 storage + minimal data transfer).
- Fast lookups (local SQLite + optional memory cache).
- Reproducible deployments (version + checksum).
- Clean separation of concerns: application code vs reference data artifact.

### Trade-offs / Risks
- Requires a build step to generate the SQLite artifact from source CSV.
- Startup time includes a download step (mitigated via small artifact size and caching at node level).
- IAM permissions are required for S3 read access (least-privilege recommended).

## Implementation Notes (Non-Normative)

- Store the processed SQLite artifact in S3, not the raw CSV.
- Consider compressing the SQLite file during transport if it provides meaningful savings.
- Use an initContainer with AWS SDK/CLI (or a minimal downloader) plus SHA256 verification.
- Ensure the S3 access path does not expose personal identifiers; use placeholder names in docs.

