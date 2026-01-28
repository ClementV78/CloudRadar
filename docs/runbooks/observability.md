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
- **Resource allocation**:
  - Requests: CPU 100m, Memory 512 Mi
  - Limits: CPU 500m, Memory 1 Gi
- **Alerting**: AlertManager included in chart but disabled (enable in Sprint 2 if needed)

### Grafana

- **Chart**: `grafana/grafana` v7.6.10
- **Admin password**: Hardcoded `admin` for MVP (change in production)
- **Datasource**: Auto-configured to Prometheus
- **Dashboards**: Starter dashboards for cluster + application health
- **Persistence**: Disabled (stateless, data loss on redeploy acceptable for MVP)
- **Resource allocation**:
  - Requests: CPU 50m, Memory 128 Mi
  - Limits: CPU 200m, Memory 256 Mi

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

## Resource Management

### CPU & Memory Requests/Limits

The observability stack is configured with minimal resources for MVP:

**Prometheus**:
- Requests: 100m CPU, 512 Mi memory
- Limits: 500m CPU, 1 Gi memory
- Monitor with: `kubectl top pod -n monitoring prometheus-*`

**Grafana**:
- Requests: 50m CPU, 128 Mi memory
- Limits: 200m CPU, 256 Mi memory

**When to scale up**:
- Prometheus: if scrape latency > 5s or OOMKilled events appear
  - Increase CPU: 100m → 200m-500m
  - Increase memory: 512 Mi → 1 Gi → 2 Gi
  - Cost: negligible (k3s pods share node resources)
- Grafana: if dashboards are slow (rare), increase memory to 512 Mi

### Check resource usage

```bash
kubectl top pods -n monitoring

# Example output:
# NAME                                       CPU(m)   MEMORY(Mi)
# prometheus-kube-prometheus-prometheus-0    50m      600Mi
# grafana-0                                   20m      150Mi
```

## Alerting

### AlertManager (Disabled by default)

AlertManager is included in `kube-prometheus-stack` but **disabled for MVP**. It handles alert routing (email, Slack, PagerDuty).

To enable AlertManager:

1. Edit `k8s/apps/monitoring/prometheus-app.yaml`:
   ```yaml
   alertmanager:
     enabled: true
     config:
       global:
         resolve_timeout: 5m
       route:
         receiver: 'null'
   ```

2. Add PrometheusRules for alerts (example):
   ```yaml
   groups:
   - name: kubernetes.rules
     rules:
     - alert: PodCrashLooping
       expr: rate(kube_pod_container_status_restarts_total[5m]) > 0.1
       for: 5m
   ```

3. ArgoCD will sync and AlertManager pod will start.

**When to add** (Sprint 2+):
- Have service health checks (ingester lag, processor queue depth)
- Define alert thresholds and owners
- Integrate with incident response (Slack webhook, etc.)

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
