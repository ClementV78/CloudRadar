# OpenSky Relay MVP (Tunnel Primary + Worker Fallback)

This runbook documents the MVP routing model for OpenSky traffic:
- Primary path: `ingester (AWS) -> Cloudflare Tunnel endpoint -> private relay -> OpenSky`
- Fallback path: `ingester (AWS) -> Cloudflare Worker endpoint -> OpenSky`

## Privacy and leakage guardrails

- Never commit real local IPs, DNS names, tunnel IDs, or personal URLs.
- Use placeholders only in docs and tickets (`<PRIVATE_TUNNEL_HOST>`, `<PRIVATE_TUNNEL_ID>`, `<PRIVATE_RELAY_URL>`).
- Keep tunnel credentials and local relay config files outside Git.

## 1) Cloudflare Tunnel setup (private/local side)

Install `cloudflared` on the private host that can reach your local relay.

Create tunnel config file (example only):

```yaml
# /etc/cloudflared/config.yml
tunnel: <PRIVATE_TUNNEL_ID>
credentials-file: /etc/cloudflared/<PRIVATE_TUNNEL_ID>.json

ingress:
  - hostname: <PRIVATE_TUNNEL_HOST>
    service: http://127.0.0.1:18080
  - service: http_status:404
```

Minimal checks:

```bash
sudo systemctl status cloudflared
cloudflared tunnel info <PRIVATE_TUNNEL_ID>
```

Expected: tunnel is connected and `<PRIVATE_TUNNEL_HOST>` resolves to Cloudflare-managed ingress.

## 2) Local relay contract (private host)

Your relay must expose two endpoints used by the ingester:

- `POST /token` -> proxies OAuth token request to OpenSky auth endpoint.
- `GET /states/all?...` -> proxies states request to OpenSky API endpoint.

Recommended MVP behavior:
- Pass through OpenSky status codes and response body.
- Enforce an auth header (shared secret) on relay endpoints.
- Do not log secrets/tokens in plaintext.

Local-only config example (do not commit):

```bash
# /etc/cloudradar/opensky-relay.env
RELAY_LISTEN_ADDR=127.0.0.1:18080
RELAY_SHARED_HEADER=X-CloudRadar-Relay-Token
RELAY_SHARED_TOKEN=<PRIVATE_RELAY_SHARED_TOKEN>
OPENSKY_BASE_URL=https://opensky-network.org/api
OPENSKY_TOKEN_URL=https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token
```

## 3) Cloudflare Worker fallback

Keep worker URLs configured in SSM so fallback is a config switch only.

- Base URL placeholder: `<CLOUDFLARE_WORKER_BASE_URL>`
- Token URL placeholder: `<CLOUDFLARE_WORKER_TOKEN_URL>`

## 4) SSM + ESO wiring (MVP keys)

Create/update these parameters in AWS SSM:

- `/cloudradar/opensky/routing_mode`
- `/cloudradar/opensky/tunnel/base_url`
- `/cloudradar/opensky/tunnel/token_url`
- `/cloudradar/opensky/tunnel/auth_header` (optional)
- `/cloudradar/opensky/tunnel/auth_token` (`SecureString`, optional)
- `/cloudradar/opensky/worker/base_url`
- `/cloudradar/opensky/worker/token_url`

Then validate ESO and secret materialization:

```bash
kubectl -n cloudradar get externalsecret opensky-credentials
kubectl -n cloudradar get secret opensky-secret -o jsonpath='{.data.routing-mode}' | base64 -d
```

Note: in this MVP manifest, OpenSky routing keys are required in SSM (no per-key `optional` fallback in `ExternalSecret`).

## 5) Switch and rollback procedure

Switch to tunnel primary:

```bash
aws ssm put-parameter --name /cloudradar/opensky/routing_mode --type String --overwrite --value "tunnel-primary"
kubectl -n cloudradar rollout restart deploy/ingester
```

Rollback to worker fallback:

```bash
aws ssm put-parameter --name /cloudradar/opensky/routing_mode --type String --overwrite --value "worker-fallback"
kubectl -n cloudradar rollout restart deploy/ingester
```

Validate mode and traffic:

```bash
kubectl -n cloudradar logs deploy/ingester --tail=120 | grep -E "OpenSky routing mode selected|Fetched|OpenSky fetch failed"
```

## 6) Incident quick checklist

1. Tunnel connected (`cloudflared` status).
2. Local relay healthy (`/token` and `/states/all` reachable from tunnel host).
3. ESO secret synced (`opensky-credentials` ready).
4. Ingester running with expected mode.
5. If primary fails, switch SSM mode to `worker-fallback` and restart ingester.
