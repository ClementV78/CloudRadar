# Runbook: admin scale API (ingester)

Purpose: scale the ingester deployment via a small admin API that talks to the Kubernetes API using a ServiceAccount token. This enables UI-driven operations while keeping the internal token hidden from end users.

## Overview (DevOps flow)
```mermaid
flowchart LR
  user[User UI] -->|HTTPS + Basic Auth| edge[Edge Nginx]
  edge -->|Internal header token| svc[Admin API Service]
  svc --> pod[Admin API Pod]
  pod -->|ServiceAccount token| k8s[Kubernetes API]
  k8s -->|patch deployments/scale| deploy[ingester Deployment]

  admin[Admin via SSM] -->|curl + internal token| svc
```

## Prerequisites
- Edge Nginx is deployed and reachable.
- The admin scale app is deployed under `k8s/apps/admin-scale`.
- The internal token exists in **two places**:
  - SSM parameter for edge to inject (e.g. `/cloudradar/edge/admin-token`).
  - Kubernetes Secret `admin-scale-token` (same value).

## Steps

### 1) Build and publish the image
```bash
docker build -t ghcr.io/clementv78/cloudradar-admin-scale:0.1.0 src/admin-scale
docker push ghcr.io/clementv78/cloudradar-admin-scale:0.1.0
```

### 2) Create the admin token
Generate a strong token locally (example):
```bash
openssl rand -hex 32
```

### 3) Store the token in SSM (edge)
```bash
aws ssm put-parameter \
  --name "/cloudradar/edge/admin-token" \
  --type SecureString \
  --value "<token>" \
  --overwrite
```

### 4) Store the token in Kubernetes (admin-scale)
Do **not** commit the token in git. Apply a Secret locally:
```bash
kubectl -n cloudradar create secret generic admin-scale-token \
  --from-literal=token="<token>" \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 5) Deploy the manifests
```bash
kubectl apply -k k8s/apps/admin-scale
```

### 6) Validate scaling
From an SSM session (or any network path that can reach the service):
```bash
curl -s -X POST \
  -H "X-Internal-Token: <token>" \
  -H "Content-Type: application/json" \
  --data '{"replicas": 0}' \
  http://<node-ip>:32737/admin/ingester/scale
```

Then verify:
```bash
kubectl -n cloudradar get deploy ingester
```

## Notes
- The ServiceAccount RBAC is restricted to `deployments/scale` for `ingester` only.
- The edge injects `X-Internal-Token` after Basic Auth; users never see the token.
- If you rotate the token, update **both** SSM and the Kubernetes Secret.
