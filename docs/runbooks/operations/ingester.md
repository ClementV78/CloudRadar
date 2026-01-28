# Ingester Runbook

Runbook for the OpenSky ingester service (Java 17 / Spring Boot).

## Prerequisites

- OpenSky OAuth client credentials available.
- Redis available (local Docker or k8s `redis` service).
- For AWS runtime secrets, see `docs/runbooks/aws-account-bootstrap.md` (OpenSky SSM parameters).

## Configuration (env vars + defaults)

### Ingestion cadence

- `INGESTER_REFRESH_MS` (default `10000`) -> base refresh interval (seconds = ms / 1000)
- `INGESTER_REFRESH_WARN_MS` (default `30000`) -> refresh interval after warn-80 threshold
- `INGESTER_REFRESH_CRITICAL_MS` (default `300000`) -> refresh interval after warn-95 threshold

### OpenSky credits thresholds

- `OPENSKY_CREDITS_QUOTA` (default `4000`) -> daily credits
- `OPENSKY_CREDITS_WARN_50` (default `50`) -> warn threshold (% consumed)
- `OPENSKY_CREDITS_WARN_80` (default `80`) -> warn threshold (% consumed)
- `OPENSKY_CREDITS_WARN_95` (default `95`) -> critical threshold (% consumed)

### OpenSky bbox (default ~225 km around Paris)

- `OPENSKY_LAT_MIN` (default `46.8296`)
- `OPENSKY_LAT_MAX` (default `50.8836`)
- `OPENSKY_LON_MIN` (default `-0.7389`)
- `OPENSKY_LON_MAX` (default `5.4433`)

### Redis

- `REDIS_HOST` (default `redis.data.svc.cluster.local`)
- `REDIS_PORT` (default `6379`)
- `INGESTER_REDIS_KEY` (default `cloudradar:ingest:queue`)

### OpenSky auth (SSM or direct)

- `OPENSKY_CLIENT_ID` / `OPENSKY_CLIENT_SECRET` (optional direct credentials)
- `OPENSKY_CLIENT_ID_SSM` / `OPENSKY_CLIENT_SECRET_SSM` (default `/cloudradar/opensky/client_id` and `/cloudradar/opensky/client_secret`)
- `OPENSKY_BASE_URL_SSM` / `OPENSKY_TOKEN_URL_SSM` (optional SSM parameter names for custom OpenSky endpoints)

## Local development

### 1) Start Redis locally

```bash
docker run -d --name redis-local -p 6379:6379 redis:7-alpine
```

### 2) Run the ingester

```bash
export OPENSKY_CLIENT_ID="<client-id>"
export OPENSKY_CLIENT_SECRET="<client-secret>"
export REDIS_HOST=localhost
export REDIS_PORT=6379
export INGESTER_REFRESH_MS=30000

mvn -q -f src/ingester/pom.xml -DskipTests package
mvn -q -f src/ingester/pom.xml spring-boot:run
```

Default bbox is ~225 km around Paris and the refresh interval is 10s.

### 3) Validate events in Redis

```bash
docker exec redis-local redis-cli llen cloudradar:ingest:queue
docker exec redis-local redis-cli lrange cloudradar:ingest:queue 0 0
```

## Kubernetes deployment

### 1) Store OpenSky credentials in SSM

Follow `docs/runbooks/aws-account-bootstrap.md` to create:

- `/cloudradar/opensky/client_id`
- `/cloudradar/opensky/client_secret`
- `/cloudradar/opensky/base_url` (optional)
- `/cloudradar/opensky/token_url` (optional)

Ensure the k3s node IAM role can read them (`ssm:GetParameter`).

### 2) Deploy the ingester manifests

```bash
kubectl apply -k k8s/apps/ingester
kubectl -n cloudradar get pods
kubectl -n cloudradar logs deploy/ingester --tail=50
```

Note: the ingester deployment is set to `replicas: 0` by default. Use the admin scale API runbook
to enable it when needed: `docs/runbooks/admin-scale.md`.

### 3) Validate health + metrics

```bash
kubectl -n cloudradar port-forward svc/ingester 8080:80
curl -s http://localhost:8080/healthz
curl -s http://localhost:8080/metrics/prometheus | head -n 5
```

## Metrics & alerts

### Prometheus endpoints

- `/metrics/prometheus` -> Prometheus format
- `/metrics/actuator` -> list of metric names (Actuator JSON)
- `/healthz` -> health probes

### Key metrics

Base counters:
- `ingester.fetch.total` (states fetched)
- `ingester.fetch.requests.total` (requests made)
- `ingester.push.total` (events pushed to Redis)
- `ingester.errors.total` (failed cycles)

OpenSky credit tracking:
- `ingester.opensky.credits.remaining`
- `ingester.opensky.requests.since_reset`
- `ingester.opensky.credits.used.since_reset`
- `ingester.opensky.credits.avg_per_request`
- `ingester.opensky.events.avg_per_request`
- `ingester.opensky.bbox.area.square_degrees`
- `ingester.opensky.quota`
- `ingester.opensky.threshold.warn50` / `warn80` / `warn95`

### Throttling behavior

Credits are tracked from `X-Rate-Limit-Remaining` on each OpenSky response.
When consumption crosses thresholds (percent consumed):

- >= 50% -> log warn threshold reached
- >= 80% -> refresh interval switches to `INGESTER_REFRESH_WARN_MS`
- >= 95% -> refresh interval switches to `INGESTER_REFRESH_CRITICAL_MS`

When credits reset (remaining increases), per-period counters reset automatically.

## Cleanup

```bash
docker rm -f redis-local
```
