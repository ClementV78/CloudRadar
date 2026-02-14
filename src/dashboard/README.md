# CloudRadar Dashboard API

Java 17 / Spring Boot service exposing frontend-oriented endpoints for map rendering and KPI cards.

## Endpoints

### `GET /api/flights`
Lightweight map feed (frequent refresh).

Default response fields:
- `icao24`
- `callsign`
- `lat`
- `lon`
- `heading`
- `lastSeen`
- `speed`
- `altitude`

Supported query params:
- `bbox=minLon,minLat,maxLon,maxLat`
- `since=<epoch|iso8601>`
- `limit=<int>`
- `sort=lastSeen|speed|altitude`
- `order=asc|desc`
- `militaryHint=true|false|unknown`
- `airframeType=airplane|helicopter|unknown`
- `category=<string>`
- `country=<string>`
- `typecode=<string>`

### `GET /api/flights/stream`
Server-Sent Events stream for frontend refresh triggers.

Events:
- `connected`
- `batch-update` (sent when latest `opensky_fetch_epoch` changes)
- `heartbeat` (keepalive)

### `GET /api/flights/{icao24}`
Aircraft detail view for click interactions.

Query params:
- `include=track,enrichment` (optional)

### `GET /api/flights/metrics`
Aggregated KPIs for dashboard cards under the map.

Query params:
- `bbox=minLon,minLat,maxLon,maxLat` (optional)
- `window=<duration>` where duration is `30m`, `6h`, `24h`, `2d`.

Response includes:
- `activeAircraft`
- `trafficDensityPer10kKm2`
- `militarySharePercent`
- `defenseActivityScore`
- `fleetBreakdown[]`
- `aircraftSizes[]`
- `aircraftTypes[]`
- `activitySeries[]`
- `estimates` (planned placeholders for v1.1 signals)

## Security and hardening
- Read-only API (GET).
- CORS allowlist (`API_CORS_ALLOW_ORIGINS`).
- In-memory per-client rate limit (`API_RATE_LIMIT_*`).
- Strict query validation (`400` on invalid values).
- Prometheus metrics at `/metrics/prometheus`.

## v1.1 notes (planned)
- Add estimated takeoffs/landings counters based on `on_ground` transition logic.
- Add `noiseProxyIndex` heuristic using speed, altitude, and aircraft class/type.
- Tracking issue: #425.

## De-scoped from issue #129
- Alerts endpoint is intentionally out of scope here and tracked via a dedicated v1 issue.
- Tracking issue: #424.
