# Observability Stack (Prometheus + Grafana)

## Overview

Prometheus scrapes metrics from all k3s services. Grafana provides dashboards for ingestion/processing health monitoring.

**Stack**: Prometheus (7d retention, 5GB PVC) + Grafana (stateless, no persistence)  
**Cost**: ~$0.50/month (Prometheus PVC only)

## Access

### Grafana

Port-forward to Grafana:
```bash
kubectl port-forward -n monitoring svc/grafana 3000:80
```

Then open http://localhost:3000

**Default credentials:**
- User: `admin`
- Password: `admin` (change in production)

### Prometheus

Port-forward to Prometheus:
```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
```

Then open http://localhost:9090

## Components

### Prometheus

- **Chart**: `prometheus-community/kube-prometheus-stack` v60.0.2
- **Namespace**: `monitoring`
- **Retention**: 7 days (MVP, data-flexible: if accumulation < 4 GB will keep all 7 days)
- **Storage**: 5 GB gp3 EBS
- **Scrape interval**: 30 seconds (default)
- **Targets**: All k3s nodes + services with `prometheus.io/scrape=true` label

### Grafana

- **Chart**: `grafana/grafana` v7.6.10
- **Admin password**: Hardcoded `admin` for MVP (change in production)
- **Datasource**: Auto-configured to Prometheus
- **Dashboards**: Starter dashboards for cluster + application health
- **Persistence**: Disabled (stateless, data loss on redeploy acceptable for MVP)

## Metrics

### Available metrics (from Prometheus)

- **Node**: CPU, memory, disk, network (via `node-exporter`)
- **Kubernetes**: Pod, deployment, statefulset metrics (via `kube-state-metrics`)
- **Applications**: Custom metrics from ingester/processor if instrumented

### Example Prometheus queries

```promql
# CPU usage per pod
sum by (pod) (rate(container_cpu_usage_seconds_total[5m]))

# Memory usage
sum by (pod) (container_memory_usage_bytes)

# Pod restart count
kube_pod_container_status_restarts_total

# Node disk usage
node_filesystem_avail_bytes / node_filesystem_size_bytes
```

## Dashboards

### Starter dashboards included

- **Kubernetes Cluster**: Overview of cluster health, nodes, pods
- **Kubernetes Apps**: Deployment/statefulset health

Add custom dashboards:
1. Port-forward to Grafana (see Access section)
2. Create dashboard or import JSON
3. Save with folder `default` for persistence

## Troubleshooting

### Prometheus not scraping metrics

Check service discovery:
```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
# Open http://localhost:9090/service-discovery
# Verify targets are discovered
```

Check scrape config:
```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
# Open http://localhost:9090/config
```

### Grafana slow / high memory

Restart Grafana:
```bash
kubectl rollout restart deployment grafana -n monitoring
```

Check resources:
```bash
kubectl top pods -n monitoring
```

## Scaling

### Increase Prometheus retention (beyond 2 days)

Edit `k8s/apps/monitoring/prometheus-app.yaml`:
```yaml
retention: 7d           # from 2d
retentionSize: "20GB"   # from 4GB
storage: 20Gi           # from 5Gi
```

**Cost impact**: +$2-3/month per 10 GB added.

### Add persistent Grafana

Edit `k8s/apps/monitoring/grafana-app.yaml`:
```yaml
persistence:
  enabled: true
  size: 1Gi
```

**Cost impact**: +$0.10/month.

## Related

- [ADR: Observability Strategy](../../architecture/decisions/)
- [Backup Strategy](#126): Prometheus backup CronJob (Sprint 2)
- [Restore Workflow (#25)](#25): Backup/restore automation
