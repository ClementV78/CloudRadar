# ADR-0017: CloudWatch datasource for AWS-native signals

## Status
Accepted

## Context
Prometheus covers Kubernetes and application metrics well, but it cannot provide AWS-native visibility (e.g., EC2/EBS/AutoScaling/S3) or VPC-level network flow analysis by itself.

The MVP goals include interview-friendly operational visibility with minimal additional infrastructure and low ongoing cost.

## Decision
- Keep **Prometheus + Grafana** as the primary observability stack for Kubernetes and application metrics (ADR-0005).
- Add a **Grafana CloudWatch datasource** to query AWS-native **CloudWatch Metrics** and **CloudWatch Logs Insights**.
- Enable **VPC Flow Logs** (CloudWatch Logs destination) via Terraform, with configurable retention.
- Use **EC2 instance role credentials** (AWS SDK default chain, IMDSv2) instead of static access keys.

## Consequences
- Adds a managed dependency (CloudWatch) to the observability story, but only for AWS-native signals.
- VPC Flow Logs can generate cost (GB ingested + storage). Mitigation: low retention (default 3d) and explicit enable flag.
- Requires additional read-only IAM permissions on the k3s node instance role.
  - Security note: without IRSA (k3s on EC2), the node role is a shared credential source at the host level.
  - Mitigation: read-only permissions and scope Logs access to `/cloudradar/*` log groups.

## References
- ADR-0005: Observability with Prometheus + Grafana
- Issue #317: feat(infra): add VPC Flow Logs and CloudWatch datasource for AWS dashboards
