# Health Endpoint (edge -> k3s)

Purpose: expose a `/healthz` endpoint through the edge Nginx to validate end-to-end connectivity and return non-sensitive cluster aggregates.

## Prerequisites
- ArgoCD is installed and synced.
- Edge Nginx is deployed and reachable.
- Metrics server is available if you want CPU/RAM aggregates (optional).

## Steps
1) Build and publish the health image to GHCR:
```bash
docker build -t ghcr.io/clementv78/cloudradar-health:0.1.0 src/health
docker push ghcr.io/clementv78/cloudradar-health:0.1.0
```

2) Configure the health NodePort for the edge (Traefik HTTP NodePort):
```hcl
edge_health_nodeport = 32736
```

If you reuse the same NodePort as the dashboard (default), you can omit `edge_health_nodeport`.

3) Apply Terraform in the live env:
```bash
terraform apply -var-file=terraform.tfvars
```

4) Commit and push the GitOps manifests under `k8s/apps/health`.
ArgoCD will sync automatically.

5) Verify from the Internet:
```bash
curl -k -u "<user>:<password>" https://<edge-public-ip>/healthz
```

## Expected response (example)
```json
{
  "status": "ok",
  "timestamp": "2026-01-16T16:10:00Z",
  "cluster": { "nodes_ready": 2, "nodes_total": 2 },
  "workloads": { "deployments": 1, "pods": 3 },
  "metrics": { "available": true, "cpu_mcores": 120.5, "memory_bytes": 203341824 },
  "errors": []
}
```

## Notes
- `/healthz` is protected by the edge Basic Auth.
- If metrics are not available, the response will include `"metrics": {"available": false}`.
