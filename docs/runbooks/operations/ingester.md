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

### OpenSky auth + routing mode

- `OPENSKY_CLIENT_ID` / `OPENSKY_CLIENT_SECRET` (required credentials)
- `OPENSKY_ROUTING_MODE` (default `direct`)
  - `direct`: use `OPENSKY_BASE_URL` + `OPENSKY_TOKEN_URL`
  - `tunnel-primary`: use `OPENSKY_TUNNEL_BASE_URL` + `OPENSKY_TUNNEL_TOKEN_URL`
  - `worker-fallback`: use `OPENSKY_WORKER_BASE_URL` + `OPENSKY_WORKER_TOKEN_URL`
- `OPENSKY_BASE_URL` / `OPENSKY_TOKEN_URL` (used in `direct` mode)
- `OPENSKY_TUNNEL_BASE_URL` / `OPENSKY_TUNNEL_TOKEN_URL` (used in `tunnel-primary` mode)
- `OPENSKY_WORKER_BASE_URL` / `OPENSKY_WORKER_TOKEN_URL` (used in `worker-fallback` mode)
- `OPENSKY_RELAY_AUTH_HEADER` / `OPENSKY_RELAY_AUTH_TOKEN` (optional, sent only in `tunnel-primary` mode)

## Local development

### 1) Start Redis locally

```bash
docker run -d --name redis-local -p 6379:6379 redis:7-alpine
```

### 2) Run the ingester

```bash
export OPENSKY_CLIENT_ID="<client-id>"
export OPENSKY_CLIENT_SECRET="<client-secret>"
export OPENSKY_ROUTING_MODE=direct
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
- `/cloudradar/opensky/base_url`
- `/cloudradar/opensky/token_url`
- `/cloudradar/opensky/routing_mode` (`direct` | `tunnel-primary` | `worker-fallback`)
- `/cloudradar/opensky/tunnel/base_url` (placeholder pattern: `<PRIVATE_TUNNEL_BASE_URL>`)
- `/cloudradar/opensky/tunnel/token_url` (placeholder pattern: `<PRIVATE_TUNNEL_TOKEN_URL>`)
- `/cloudradar/opensky/tunnel/auth_header` (optional)
- `/cloudradar/opensky/tunnel/auth_token` (optional, `SecureString`)
- `/cloudradar/opensky/worker/base_url` (placeholder pattern: `<CLOUDFLARE_WORKER_BASE_URL>`)
- `/cloudradar/opensky/worker/token_url` (placeholder pattern: `<CLOUDFLARE_WORKER_TOKEN_URL>`)

Ensure the k3s node IAM role can read them (`ssm:GetParameter`).
Never commit real tunnel hostnames, local IPs, or personal endpoints in Git.

### 2) Deploy the ingester manifests

```bash
kubectl apply -k k8s/apps/ingester
kubectl -n cloudradar get pods
kubectl -n cloudradar logs deploy/ingester --tail=50
```

Note: the ingester deployment is set to `replicas: 0` by default. Use the admin scale API runbook
to enable it when needed: `docs/runbooks/admin-scale.md`.

### 2.1) Switch routing mode (MVP rollout/rollback)

Switch to tunnel-primary:

```bash
aws ssm put-parameter --name /cloudradar/opensky/routing_mode --type String --overwrite --value "tunnel-primary"
kubectl -n cloudradar rollout restart deploy/ingester
kubectl -n cloudradar logs deploy/ingester --tail=100 | grep "OpenSky routing mode selected"
```

Rollback to worker-fallback:

```bash
aws ssm put-parameter --name /cloudradar/opensky/routing_mode --type String --overwrite --value "worker-fallback"
kubectl -n cloudradar rollout restart deploy/ingester
kubectl -n cloudradar logs deploy/ingester --tail=100 | grep "OpenSky routing mode selected"
```

For local/private relay and Cloudflare tunnel setup, see:
- `docs/runbooks/operations/opensky-relay-mvp.md`

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
When available, `X-Rate-Limit-Limit` is used as the effective quota for consumed-percent
calculation and refresh throttling. If missing, fallback is `OPENSKY_CREDITS_QUOTA`.
When consumption crosses thresholds (percent consumed):

- >= 50% -> log warn threshold reached
- >= 80% -> refresh interval switches to `INGESTER_REFRESH_WARN_MS`
- >= 95% -> refresh interval switches to `INGESTER_REFRESH_CRITICAL_MS`

When credits reset (remaining increases), per-period counters reset automatically.

Operational logs emitted for rate-limit diagnostics:
- `OpenSky rate-limit header detected: limit=... (configured quota=...)`
- `OpenSky header limit (...) differs from configured OPENSKY_CREDITS_QUOTA (...)`
- `OpenSky rate-limit reset header updated: reset_at_epoch_seconds=...`

### Token refresh resilience

- `OpenSkyTokenService` caches OAuth token until near expiry and avoids refreshing on each cycle.
- On consecutive token refresh failures, a local cooldown is applied:
  - `15s -> 30s -> 60s -> 120s -> 300s -> 600s`
- Token refresh failures are propagated to the ingestion job so the global ingester backoff also applies.
- A successful token refresh resets the token cooldown state.

Useful metrics for token path diagnostics:
- `ingester_opensky_token_http_requests_total{outcome="success|client_error|server_error|exception"}`
- `ingester_opensky_token_http_duration_seconds`

## Cleanup

```bash
docker rm -f redis-local
```
