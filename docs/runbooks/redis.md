# Redis (data namespace)

Purpose: run a minimal Redis instance used as the event buffer between the ingester and processor.

## Prerequisites
- k3s cluster is running.
- GitOps or kubectl access is available.

## Deploy
```bash
kubectl apply -k k8s/apps/redis
```

## Verify
```bash
kubectl -n data get pods -l app.kubernetes.io/name=redis
kubectl -n data get svc redis
```

## Notes
- Redis runs as a single-replica StatefulSet to keep storage stable across restarts.
- A PVC (5Gi) is attached at `/data` to persist the Redis append-only file.
- Readiness uses `redis-cli ping`; liveness uses a TCP socket on 6379.
- Resources are capped for MVP (requests: 50m/128Mi, limits: 250m/256Mi).
- Service DNS inside the cluster: `redis.data.svc.cluster.local:6379`.
- For MVP, no authentication is enabled (internal-only access via ClusterIP).
