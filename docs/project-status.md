# Project Status (Detailed)

This document contains the detailed, living status of CloudRadar.
For a quick summary, see the KPI table in `README.md`.

## Tracking Links

- GitHub Project: https://github.com/users/ClementV78/projects/1/
- GitHub Issues: https://github.com/ClementV78/CloudRadar/issues

---

## Progress by Category (v1-mvp)

**Infra**
- ✅ AWS account secured (MFA root, no static root keys)
- ✅ IAM baseline set (bootstrap user + MFA, bootstrap role, least-privilege policies) — see `docs/runbooks/aws-account-bootstrap.md`
- ✅ IAM OIDC for GitHub Actions configured (no static AWS keys in CI)
- ✅ Terraform backend ready (S3 state + DynamoDB lock)
- ✅ Cost guardrails enabled (budget alerts)
- ✅ Terraform bootstrap solved via a dedicated workflow using local state to create S3/DynamoDB, then remote state for all other stacks
- ✅ VPC module + per-environment live roots (dev/prod)
- ✅ Provision k3s nodes with cloud-init (server + agent) + SSM validation + retry
- ✅ Deploy edge Nginx with TLS + Basic Auth (dev)
- ✅ Expose `/healthz` through edge Nginx
- 📝 Add SQLite persistence + daily S3 backups + restore workflow

**Automation**
- ✅ Backend bootstrap workflow in GitHub Actions (local state, idempotent)
- ✅ Infra CI workflow (fmt/validate/plan + tfsec) on PRs
- ✅ Manual infra apply workflow (workflow_dispatch)
- ✅ Runbooks available for bootstrap and verification
- ✅ GitOps bootstrap with ArgoCD (k3s)
- 📝 Application CI/CD pipeline (build + GHCR publish)

**Application**
- 📝 Integrate OpenSky ingestion source (or equivalent public feed)
- 📝 Wire ingestion -> Redis -> processor -> SQLite
- 📝 Implement minimal API for dashboard queries
- ✅ Deploy Redis buffer in the data namespace
- 📝 End-to-end demo with sample telemetry data

**Monitoring**
- 📝 Deploy Prometheus + Grafana with starter dashboards
- 📝 Add logging stack (Loki + Promtail)
- 📝 Add alerting via Alertmanager

**UI**
- 📝 Grafana Geomap panel as MVP UI
