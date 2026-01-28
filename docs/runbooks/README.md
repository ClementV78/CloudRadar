# Runbooks: Execution Order

This page is the entry point for all runbooks and describes the recommended execution order.

---

## 📋 Quick Navigation

- **[Bootstrap Phase](bootstrap/)** — First-time AWS, Terraform, ArgoCD setup
- **[Operations Phase](operations/)** — Deploy services: Redis, Ingester, Processor, Health, Admin-Scale, Observability
- **[Troubleshooting](troubleshooting/)** — Issue log and common problems
- **[CI/CD Reference](ci-cd/)** — Infrastructure and application CI/CD workflows

---

## 🚀 Execution Order (Full Sequence)

### Phase 1: Bootstrap (One-time setup)

#### 1) AWS account bootstrap (required)

Purpose: establish IAM baseline, MFA, budgets, and the GitHub OIDC provider + Terraform role.

- Runbook: [bootstrap/aws-account-bootstrap.md](bootstrap/aws-account-bootstrap.md)
- IAM inventory reference: [docs/iam/inventory.md](../iam/inventory.md)

#### 2) Terraform backend bootstrap (required)

Purpose: create the S3 bucket and DynamoDB table used by Terraform state and locking.

- Runbook: [bootstrap/terraform-backend-bootstrap.md](bootstrap/terraform-backend-bootstrap.md)

#### 3) ArgoCD bootstrap (one-time)

Purpose: deploy ArgoCD inside k3s to enable GitOps.

- Runbook: [bootstrap/argocd-bootstrap.md](bootstrap/argocd-bootstrap.md)

Post-bootstrap checks (optional):
- Verify the ArgoCD Application status (Synced/Healthy).
- Retrieve the initial admin password (if needed).

---

### Phase 2: Reference (Understand CI/CD before applying)

#### 2.1) CI infrastructure workflow reference (recommended)

Purpose: understand what `ci-infra` validates on PRs and how manual apply works.

- Runbook: [ci-cd/ci-infra.md](ci-cd/ci-infra.md)

#### 2.2) App CI/CD workflow reference (recommended)

Purpose: understand how multi-service Docker images are built and pushed to GHCR.

- Runbook: [ci-cd/ci-app.md](ci-cd/ci-app.md)
- Services: ingester, processor, dashboard, health, admin-scale
- Triggers: PR (build only), push to main (build + push), tags (semver push)

---

### Phase 3: Terraform Live Roots (per environment)

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

#### 3.1) Capture infra outputs (optional)

Purpose: generate a local Markdown snapshot of Terraform outputs for quick reference.

```bash
./scripts/update-infra-outputs.sh dev
# If the local backend is not initialized (or you want to reconfigure it), use:
./scripts/update-infra-outputs.sh dev --init
```

This writes [ci-cd/infra-outputs.md](ci-cd/infra-outputs.md) locally. The file is intentionally not committed.

---

### Phase 4: Deploy Services (ArgoCD Applications)

All services below are deployed via ArgoCD Applications to k3s:

#### 4.1) Redis buffer (data namespace)

Purpose: deploy the Redis buffer used by ingester/processor.

- Runbook: [operations/redis.md](operations/redis.md)

#### 4.2) OpenSky ingester

Purpose: run the Java ingester locally or deploy it to k3s.

- Runbook: [operations/ingester.md](operations/ingester.md)

#### 4.3) Processor aggregates

Purpose: consume Redis events and build in-memory aggregates for the UI.

- Runbook: [operations/processor.md](operations/processor.md)

#### 4.4) Health endpoint (optional)

Purpose: expose `/healthz` via the edge Nginx to validate end-to-end k3s connectivity.

- Runbook: [operations/health-endpoint.md](operations/health-endpoint.md)

#### 4.5) Admin-Scale API (optional)

Purpose: scale the ingester deployment via a small admin API that talks to the K8s API.

- Runbook: [operations/admin-scale.md](operations/admin-scale.md)

#### 4.6) Observability Stack (Prometheus + Grafana)

Purpose: deploy Prometheus (metrics collection) and Grafana (dashboards) for cluster and application health monitoring.

- Runbook: [operations/observability.md](operations/observability.md)
- Stack: Prometheus (7d retention, 5GB PVC) + Grafana (stateless, auto-datasource)
- Cost: ~$0.50/month
- Access: Port-forward for local development

---

### Phase 5: Troubleshooting

#### Issue Log

Short, running log of issues encountered and how they were resolved.

- Journal: [troubleshooting/issue-log.md](troubleshooting/issue-log.md)

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
