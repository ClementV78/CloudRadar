# CloudRadar Dashboard Service

Java 17 / Spring Boot service that exposes read APIs consumed by the React dashboard.

This README focuses on **runtime behavior and internals**.
For HTTP contract details, see `docs/api/dashboard-api.md`.

## Purpose

- Serve map payloads from Redis aggregates.
- Serve per-aircraft detail payloads with optional enrichment.
- Serve KPI aggregates for frontend cards/charts.
- Push refresh signals to frontend through SSE when a new OpenSky batch is detected.

## Endpoints (high-level)

Base path: `/api/flights`

- `GET /api/flights` -> map list payload
- `GET /api/flights/{icao24}` -> detail payload
- `GET /api/flights/metrics` -> KPI payload
- `GET /api/flights/stream` -> SSE events (`connected`, `batch-update`, `heartbeat`)

See `docs/api/dashboard-api.md` for full request/response schemas and examples.

## Internal Flow

### 1. Map list (`GET /api/flights`)

Implemented in `FlightQueryService#listFlights`.

Pipeline:

1. Parse and validate query parameters (`bbox`, `limit`, `sort`, filters).
2. Scan Redis hash `cloudradar:aircraft:last` (key configurable).
3. Build in-memory candidates with normalized `icao24`.
4. Batch continuity policy:
   - select latest `opensky_fetch_epoch` as primary snapshot,
   - include ICAO from up to two previous batches when missing in latest batch (short continuity fallback).
5. Enrich metadata on read (if aircraft DB enabled):
   - category, country, typecode, military hint,
   - inferred fields used by UI (`airframeType`, `fleetType`, `aircraftSize`).
6. Filter/sort/limit and return frontend payload.

### 2. Detail (`GET /api/flights/{icao24}`)

Implemented in `FlightQueryService#getFlightDetail`.

Pipeline:

1. Read latest position from Redis hash by `icao24`.
2. Optionally load track points from `cloudradar:aircraft:track:<icao24>`.
3. Optionally enrich with aircraft metadata from local SQLite repository.
4. Return a single merged DTO.

### 3. Metrics (`GET /api/flights/metrics`)

Implemented in `FlightQueryService#getFlightsMetrics`.

Pipeline:

1. Reuse snapshot loading logic (bbox/window aware).
2. Aggregate active aircraft, density, defense share, type/size/fleet breakdowns.
3. Build activity series buckets for lightweight frontend sparklines.

### 4. Refresh stream (`GET /api/flights/stream`)

Implemented in `FlightUpdateStreamService`.

Pipeline:

1. Register SSE emitter.
2. Poll Redis latest batch epoch in background.
3. Emit `batch-update` when epoch changes.
4. Emit periodic `heartbeat` keepalive.
5. Frontend subscribes with `EventSource` and refreshes on push.

## Data Sources

- Redis (required):
  - last positions hash
  - track lists
- Aircraft metadata DB (optional):
  - local SQLite file (`/refdata/aircraft.db` by default)
  - enabled through `dashboard.aircraft-db.enabled`

## Config Highlights

From `src/dashboard/src/main/resources/application.yml`:

- Redis:
  - `REDIS_HOST`, `REDIS_PORT`
  - `DASHBOARD_REDIS_LAST_POSITIONS_KEY`
  - `DASHBOARD_REDIS_TRACK_KEY_PREFIX`
- API behavior:
  - `API_LIMIT_DEFAULT`, `API_LIMIT_MAX`
  - `API_BBOX_*`
  - `API_CORS_ALLOW_ORIGINS`
  - `API_RATE_LIMIT_*`
- Aircraft DB:
  - `API_AIRCRAFT_DB_ENABLED`
  - `API_AIRCRAFT_DB_PATH`
  - `API_AIRCRAFT_DB_CACHE_SIZE`

## Security and Hardening

- Read-only API surface (`GET` only).
- API validation and structured 400/404 errors.
- In-memory per-client API rate limiting on `/api/**`.
- CORS allowlist support.

## Local Run

```bash
cd src/dashboard
mvn spring-boot:run
```

Run tests:

```bash
mvn test
```

## Related Docs

- API reference: `docs/api/dashboard-api.md`
- Architecture deep dive: `docs/architecture/frontend-dashboard-technical-architecture.md`
- Project architecture overview: `docs/architecture/application-architecture.md`
