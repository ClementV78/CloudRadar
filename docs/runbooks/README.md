# Runbooks: Execution Order

This page is the entry point for all runbooks and describes the recommended execution order.

## 0) GitHub Workflow & Project Management (foundational)

Purpose: understand issue/PR templates, metadata requirements, and automated checks.

- Reference: `docs/runbooks/github-workflow.md`
- Covers: templates, automated metadata verification, best practices for issues and PRs

## 1) AWS account bootstrap (required)

Purpose: establish IAM baseline, MFA, budgets, and the GitHub OIDC provider + Terraform role.

- Runbook: `docs/runbooks/aws-account-bootstrap.md`
- IAM inventory reference: `docs/iam/inventory.md`

## 2) Terraform backend bootstrap (required)

Purpose: create the S3 bucket and DynamoDB table used by Terraform state and locking.

- Runbook: `docs/runbooks/bootstrap/terraform-backend-bootstrap.md`

## 2.1) CI workflow reference (recommended)

Purpose: understand what `ci-infra` validates on PRs and how manual apply works.

- Runbook: `docs/runbooks/ci-infra.md`

## 2.2) App CI/CD workflow reference (recommended)

Purpose: understand how multi-service Docker images are built and pushed to GHCR.

- Runbook: `docs/runbooks/ci-app.md`
- Services: ingester, processor, frontend, dashboard, health, admin-scale
- Triggers: PR (build only), push to main (build + push), tags (semver push)

## 2.3) Nightly performance baseline (recommended)

Purpose: run a lightweight scheduled API performance baseline outside the PR critical path.

- Runbook: `docs/runbooks/ci-cd/k6-nightly-baseline.md`
- Workflow: `.github/workflows/k6-nightly-baseline.yml`

## 2.4) SonarCloud quality gate (recommended)

Purpose: run repository quality analysis and enforce the SonarCloud quality gate on PR/main.

- Runbook: `docs/runbooks/ci-cd/sonarcloud.md`
- Workflow: `.github/workflows/sonarcloud.yml`

## 3) Terraform live roots (per environment)

Purpose: initialize and validate the Terraform roots for each environment.

1. Copy `backend.hcl.example` to `backend.hcl` in each env folder and set the real bucket/table names.
2. Run `terraform init -backend-config=backend.hcl`.
3. Run `terraform validate` and `terraform plan` with the env tfvars.
   - `terraform.tfvars.example` is a reference template only and is not used by CI.

Example for dev:

```bash
cd infra/aws/live/dev
cp backend.hcl.example backend.hcl
terraform init -backend-config=backend.hcl
terraform validate
terraform plan -var-file=terraform.tfvars
```

Example for prod:

```bash
cd infra/aws/live/prod
cp backend.hcl.example backend.hcl
terraform init -backend-config=backend.hcl
terraform validate
terraform plan -var-file=terraform.tfvars
```

## 3.1) Capture infra outputs (optional)

Purpose: generate a local Markdown snapshot of Terraform outputs for quick reference.

```bash
./scripts/update-infra-outputs.sh dev
# If the local backend is not initialized (or you want to reconfigure it), use:
./scripts/update-infra-outputs.sh dev --init
```

This writes `docs/runbooks/infra-outputs.md` locally. The file is intentionally not committed.

## 3.2) ArgoCD bootstrap (one-time)

Purpose: deploy ArgoCD inside k3s to enable GitOps.

- Runbook: `docs/runbooks/argocd-bootstrap.md`

Post-bootstrap checks (optional):
- Verify the ArgoCD Application status (Synced/Healthy).
- Retrieve the initial admin password (if needed).

## 3.2.1) External Secrets Operator (one-time, after ArgoCD)

Purpose: set up External Secrets Operator to sync secrets from AWS SSM Parameter Store into Kubernetes Secrets.

- Runbook: `docs/runbooks/external-secrets-operator.md`
- Design: [ADR-0016](../architecture/decisions/ADR-0016-2026-01-29-external-secrets-operator.md)
- Implements: Issue #150
- Phases: Terraform IAM → ESO Install → SecretStore → ExternalSecrets → Deployment updates

## 3.2.2) DNS delegation (Route53, optional but recommended)

Purpose: delegate `cloudradar.<domain>` to Route53 and keep DNS updated after rebuilds.

- Runbook: `docs/runbooks/dns-delegation-route53.md`

## 3.3) Edge health endpoint (optional)

Purpose: expose `/healthz` via the edge Nginx to validate end-to-end k3s connectivity.

- Runbook: `docs/runbooks/health-endpoint.md`
- TODO: configure a Docker credential helper to avoid GHCR login warnings.

## 3.3.1) Admin scale API (optional)

Purpose: scale the ingester deployment via a small admin API that talks to the K8s API.

- Runbook: `docs/runbooks/admin-scale.md`

## 3.4) Redis buffer (data namespace)

Purpose: deploy the Redis buffer used by ingester/processor.

- Runbook: `docs/runbooks/redis.md`

## 3.5) OpenSky ingester

Purpose: run the Java ingester locally or deploy it to k3s.

- Runbook: `docs/runbooks/ingester.md`

## 3.6) Processor aggregates

Purpose: consume Redis events and build in-memory aggregates for the UI.

- Runbook: `docs/runbooks/processor.md`

## 3.6.1) Frontend dashboard UI

Purpose: run and verify the React/Leaflet frontend that consumes dashboard API endpoints.

- Runbook: `docs/runbooks/operations/frontend.md`

## 3.7) Observability stack (Prometheus + Grafana)

Purpose: deploy Prometheus (metrics collection) and Grafana (dashboards) for cluster and application health monitoring.

- Runbook: `docs/runbooks/observability.md`
- Stack: Prometheus (2d retention, 5GB) + Grafana (stateless, admin dashboard)
- Cost: ~$0.50/month
- Access: Port-forward for local development

## Troubleshooting journal

Short, running log of issues encountered and how they were resolved.

- Guide: `docs/runbooks/troubleshooting/troubleshooting-guide.md`
- Journal: `docs/runbooks/troubleshooting/issue-log.md`

## Reference diagrams

Use these diagrams as visual context while following the runbooks.

- Infrastructure (auto-generated): ![CloudRadar infrastructure diagram](../architecture/diagrams/cloudradar-infrastructure.png)
- Workloads (auto-generated): ![CloudRadar workloads diagram](../architecture/diagrams/cloudradar-workload-grid3.png)

## 4) Next steps (planned)

- Apply on dev when ready; keep prod for later.
- Add edge EC2 and observability stacks once k3s baseline is stable.
- Apply edge EC2 module for public entrypoint when ready (issue #8).
- Before edge apply: ensure the Basic Auth SSM parameter exists and the Terraform role has `ssm:PutParameter`/`iam:GetRolePolicy` (see `docs/runbooks/aws-account-bootstrap.md`).
- The Basic Auth username is set via `edge_basic_auth_user` in `infra/aws/live/<env>/terraform.tfvars`.
- Ensure `edge_root_volume_size` is at least 30 GB (40 GB recommended) for AL2023.
- TODO: Re-enable WebSocket headers in edge Nginx if the dashboard/API needs persistent connections.
