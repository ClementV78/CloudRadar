# Runbook: Frontend Dashboard (React + Leaflet)

This runbook explains how to run, build, deploy, and verify the CloudRadar frontend service.

## Scope

- Service: `frontend`
- Source: `src/frontend/`
- Kubernetes manifests: `k8s/apps/frontend/`
- Upstream API dependency: `dashboard` service (`/api/flights*`)

## Local development

```bash
cd src/frontend
npm install
npm run dev
```

Default API proxy in dev:
- `/api` -> `http://localhost:8080`

Override API origin if needed:

```bash
VITE_DEV_API_ORIGIN=http://localhost:8081 npm run dev
```

## Local production build

```bash
cd src/frontend
npm run build
```

Build output is generated under `src/frontend/dist/`.

## Container image

- Dockerfile: `src/frontend/Dockerfile`
- Runtime: `nginxinc/nginx-unprivileged`
- Health endpoint: `/healthz`

## Kubernetes deployment model

- Deployment: `k8s/apps/frontend/deployment.yaml`
- Service: `k8s/apps/frontend/service.yaml` (ClusterIP)
- Ingress: `k8s/apps/frontend/ingress.yaml` (Traefik, path `/`)

Routing behavior with edge Nginx:
- `/` -> Traefik (`30080`) -> frontend service
- `/api/*` -> dashboard API NodePort (`30081`)

## Verification checklist

1. Frontend pod is ready:

```bash
kubectl -n cloudradar get pods -l app.kubernetes.io/name=frontend
```

2. Frontend service and ingress exist:

```bash
kubectl -n cloudradar get svc frontend
kubectl -n cloudradar get ingress frontend
```

3. Frontend health endpoint is reachable in-cluster:

```bash
kubectl -n cloudradar port-forward svc/frontend 18080:80
curl -fsS http://localhost:18080/healthz
```

4. UI can load flights data through edge proxy:
- Open the dashboard URL in browser
- Validate map markers refresh every 10s
- Click one marker and confirm detail panel + track rendering

## SSE stream behavior and logs

The frontend subscribes to dashboard SSE endpoint `GET /api/flights/stream` (`EventSource`) for push refresh (`batch-update`, `heartbeat`).

Expected behavior:
- Browser tab close/reload or idle proxy timeout can terminate SSE connections.
- These disconnects are normal and should not be treated as incidents.
- Dashboard backend now classifies expected client disconnect IO errors (`broken pipe`, connection reset/abort, socket/stream closed) as normal stream lifecycle events.

Log interpretation:
- `DEBUG` disconnect messages in `FlightUpdateStreamService` are expected lifecycle noise.
- `WARN` stream send failures indicate non-expected backend faults and should be investigated.

## Known scope boundaries (v1)

- Real zones/alerts API integration is intentionally deferred:
  - zones/alerting pipeline: issue `#128`
  - alerts endpoint: issue `#424`
- Frontend currently surfaces this as "pending" in UI metadata.
