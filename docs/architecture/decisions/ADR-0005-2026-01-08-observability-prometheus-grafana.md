# ADR-0005: Observability with Prometheus + Grafana

## Status
Accepted

## Context
The MVP must demonstrate observability without managed monitoring services.

## Decision
Deploy **Prometheus** for metrics scraping and **Grafana** for dashboards.

## Consequences
- Full control over metrics collection and dashboards.
- Requires cluster resources and maintenance.
- Showcases CNCF-friendly observability stack.

## Details
- No managed monitoring services for primary Kubernetes/app metrics in v1.
- Update (2026-02-07): AWS-native signals are queried via Grafana CloudWatch datasource and VPC Flow Logs (read-only). See ADR-0017.
