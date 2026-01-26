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
- The internal token exists in SSM (edge and admin API read the same parameter).
  - Default path: `/cloudradar/edge/admin-token`.
- k3s nodes can reach SSM (NAT or VPC endpoints) and have `ssm:GetParameter` permissions.

## Secret configuration (Basic Auth + internal token)

### Edge Basic Auth (public access)
- Terraform input: `edge_basic_auth_ssm_parameter_name`
- Default SSM path: `/cloudradar/edge/basic-auth`

### Admin internal token (edge -> admin API)
- Terraform input: `edge_admin_token_ssm_parameter_name`
- Default SSM path: `/cloudradar/edge/admin-token`

The admin API reads the token directly from SSM (same source as the edge).

## Auth behavior (implementation detail)
- The API expects the header `X-Internal-Token`.
- The token is loaded from SSM using `ADMIN_TOKEN_SSM_NAME` and `AWS_REGION`.
- In code: `src/admin-scale/app.py` compares the header value to the SSM token and returns **401** when invalid.

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

### 4) Deploy the manifests
```bash
kubectl apply -k k8s/apps/admin-scale
```

### 5) Validate scaling
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
- If you rotate the token, update the SSM parameter.
