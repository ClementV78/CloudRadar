# Dashboard API (v1-mvp)

Base path: `/api/flights`  
Audience: frontend dashboard map + detail panel + KPI cards.

## `GET /api/flights`

Returns a lightweight list for map rendering.

Query parameters:
- `bbox` (optional): `minLon,minLat,maxLon,maxLat`
- `since` (optional): lower time bound (epoch seconds or ISO-8601)
- `limit` (optional): max number of items (clamped by server config)
- `sort` (optional): `lastSeen|speed|altitude`
- `order` (optional): `asc|desc`
- `militaryHint` (optional): `true|false|unknown`
- `airframeType` (optional): `airplane|helicopter|unknown`
- `category` (optional): free text filter
- `country` (optional): free text filter
- `typecode` (optional): ICAO type code filter

Example request:
```bash
curl "http://localhost:8080/api/flights?bbox=0.0,45.0,10.0,55.0&since=2026-02-13T10:00:00Z&limit=2&sort=lastSeen&order=desc"
```

Example response:
```json
{
  "items": [
    {
      "icao24": "abc123",
      "callsign": "AFR123",
      "lat": 48.85,
      "lon": 2.35,
      "heading": 90.0,
      "lastSeen": 1760000000,
      "speed": 220.0,
      "altitude": 10500.0
    }
  ],
  "count": 1,
  "totalMatched": 1,
  "limit": 2,
  "bbox": {
    "minLon": 0.0,
    "minLat": 45.0,
    "maxLon": 10.0,
    "maxLat": 55.0
  },
  "timestamp": "2026-02-13T12:00:00Z"
}
```

## `GET /api/flights/{icao24}`

Returns detailed data for one aircraft.

Path parameter:
- `icao24` (required): 6-char hexadecimal identifier

Query parameters:
- `include` (optional): comma-separated values (`track`, `enrichment`)

Notes:
- `include=track` enables recent track points.
- Enrichment fields are returned when available from metadata repository.

Example request:
```bash
curl "http://localhost:8080/api/flights/abc123?include=track"
```

Example response:
```json
{
  "icao24": "abc123",
  "callsign": "AFR123",
  "registration": "F-GKXA",
  "manufacturer": "Airbus",
  "model": "A320",
  "typecode": "A320",
  "category": "Commercial",
  "lat": 48.85,
  "lon": 2.35,
  "heading": 90.0,
  "altitude": 10800.0,
  "groundSpeed": 220.0,
  "verticalRate": 0.0,
  "lastSeen": 1760000000,
  "onGround": false,
  "country": "France",
  "militaryHint": false,
  "yearBuilt": 2011,
  "ownerOperator": "Air France",
  "recentTrack": [
    {
      "lat": 48.80,
      "lon": 2.20,
      "heading": 88.0,
      "altitude": 10700.0,
      "groundSpeed": 218.0,
      "lastSeen": 1759999970,
      "onGround": false
    }
  ],
  "timestamp": "2026-02-13T12:00:00Z"
}
```

Not found example (`404`):
```json
{
  "error": "not_found",
  "message": "flight not found for icao24=abc123",
  "timestamp": "2026-02-13T12:00:00Z"
}
```

## `GET /api/flights/metrics`

Returns aggregated metrics for the dashboard cards/charts.

Query parameters:
- `bbox` (optional): aggregation area
- `window` (optional): duration (`30m`, `6h`, `24h`, `2d`)

Example request:
```bash
curl "http://localhost:8080/api/flights/metrics?bbox=0.0,45.0,10.0,55.0&window=24h"
```

Example response:
```json
{
  "activeAircraft": 187,
  "trafficDensityPer10kKm2": 42.3,
  "militarySharePercent": 12.0,
  "defenseActivityScore": 12.0,
  "fleetBreakdown": [
    { "key": "commercial", "count": 146, "percent": 78.07 },
    { "key": "military", "count": 22, "percent": 11.76 }
  ],
  "aircraftSizes": [
    { "key": "medium", "count": 101, "percent": 54.01 }
  ],
  "aircraftTypes": [
    { "key": "A320", "count": 34, "percent": 18.18 }
  ],
  "activitySeries": [
    { "epoch": 1760000000, "count": 15 }
  ],
  "estimates": {
    "takeoffsWindow": null,
    "landingsWindow": null,
    "noiseProxyIndex": null,
    "notes": {
      "takeoffsLandings": "planned_v1_1",
      "noiseProxyIndex": "planned_v1_1_heuristic",
      "alerts": "out_of_scope_issue_129"
    }
  },
  "timestamp": "2026-02-13T12:00:00Z"
}
```

## Error Model

Validation error (`400`):
```json
{
  "error": "bad_request",
  "message": "invalid bbox format",
  "timestamp": "2026-02-13T12:00:00Z"
}
```

Rate limit (`429`):
```json
{
  "error": "rate limit exceeded"
}
```

## Security and Runtime Notes

- API is read-only (`GET` endpoints only).
- CORS is controlled by allowlist (`dashboard.api.cors.allowed-origins`).
- Rate limiting is applied on `/api/**`.
- Metrics are bbox-scoped when `bbox` is provided.
