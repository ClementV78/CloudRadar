# CloudRadar Frontend

Frontend dashboard for issue #130.
Stack: React 18 + Vite + Leaflet.

This service renders the live IDF map, aircraft markers, flight detail panel, and KPI cards from the Dashboard API.

## Status

- Implemented in this scope:
  - full-screen map UI with dark/cyber style
  - `dark/satellite` map modes
  - aircraft markers with heading-oriented SVG icons
  - click-to-open detail panel (desktop sidebar, mobile bottom sheet behavior)
  - KPI strip based on `/api/flights/metrics`
  - periodic refresh every 10 seconds
- Deferred by design:
  - live zones/alerts integration (`#128`, `#424`)

## Technical Architecture

### Runtime model

- Single-page app served as static files by Nginx (`/`, SPA fallback to `index.html`)
- Health endpoint exposed at `/healthz`
- API calls use relative paths (`/api/flights...`) to stay same-origin behind edge proxy

### Data flow

1. Every 10s, frontend fetches:
   - `GET /api/flights?bbox=...`
   - `GET /api/flights/metrics?bbox=...&window=6h`
2. On marker click, frontend fetches:
   - `GET /api/flights/{icao24}?include=track,enrichment`
3. Detail panel and track polyline are updated from this detail payload.

### Main UI modules

- `src/App.tsx`
  - orchestration layer (polling, selection, status computation, map rendering)
- `src/components/Header.tsx`
  - API/OpenSky status badges, UTC clock, theme switch
- `src/components/DetailPanel.tsx`
  - structured aircraft detail view using `military hint` wording
- `src/components/KpiStrip.tsx`
  - KPI cards and lightweight sparkline rendering
- `src/api.ts`
  - typed API client wrappers
- `src/types.ts`
  - frontend contracts matching backend response models
- `src/constants.ts`
  - bbox and refresh constants

### State and refresh strategy

- Polling interval: `10s` (`REFRESH_INTERVAL_MS`)
- API status:
  - `online` when refresh succeeds
  - `degraded` if refresh fails after a successful cycle
  - `offline` if no successful cycle is available
- OpenSky feed status:
  - derived from freshest `lastSeen` in flights list
  - marked `stale` when age exceeds threshold (`STALE_AFTER_SECONDS`)

### Mapping and rendering choices

- Leaflet base layers:
  - `dark`: Carto Dark tiles
  - `satellite`: Esri World Imagery
- Operational bbox is displayed with a dashed cyan rectangle
- Map movement constrained with `maxBounds`
- Track breadcrumb is rendered as polyline from `recentTrack`

## API contract used by this frontend

- `GET /api/flights`
- `GET /api/flights/{icao24}`
- `GET /api/flights/metrics`

Note: `/api/alerts` is intentionally not consumed yet (deferred to #424).

## Local development

```bash
cd src/frontend
npm install
npm run dev
```

Default dev proxy:
- `/api` -> `http://localhost:8080`

Override API origin:

```bash
VITE_DEV_API_ORIGIN=http://localhost:8081 npm run dev
```

Build production assets:

```bash
npm run build
```

## Container and deployment

- Dockerfile: `src/frontend/Dockerfile`
  - build stage: Node + Vite build
  - runtime stage: `nginxinc/nginx-unprivileged`
- Nginx config: `src/frontend/nginx.conf`
  - serves SPA
  - exposes `/healthz`

Kubernetes manifests:
- `k8s/apps/frontend/deployment.yaml`
- `k8s/apps/frontend/service.yaml`
- `k8s/apps/frontend/ingress.yaml`

## Design notes

- Visual direction follows issue #130 mockup:
  - dark glass panels
  - cyan accent (`#00F5FF`)
  - military hint accent (`#FF4B4B`)
  - JetBrains Mono for instrumentation-style labels
- Mobile behavior is intentionally adapted from desktop:
  - detail panel becomes a bottom sheet style panel
  - KPI cards stack vertically
