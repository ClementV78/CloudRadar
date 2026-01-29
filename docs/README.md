# CloudRadar Documentation Hub

**This is the central entry point for all CloudRadar documentation.**

For quick project overview, see [root README.md](../README.md).  
For agent guidance, see [AGENTS.md](../AGENTS.md) (project management & workflow rules).

---

## 📍 Where to Find What

### 💻 **GitHub Workflow & Project Management**

If you're creating issues or PRs, start here:

- **[GitHub Workflow Guide](runbooks/github-workflow.md)** — Issue/PR templates, metadata requirements, automated checks
  - How to create issues and PRs correctly
  - What metadata is required and why
  - How the automated metadata verification workflow works
  - Best practices and complete examples

---

### 🏗️ **Understanding the Architecture**

Start here if you're new to the project or want to understand how components fit together:

1. **[Application Architecture](architecture/application-architecture.md)** (5 microservices)
   - Overview diagram of entire data flow
   - Ingester, Processor, Health, Admin-Scale services
   - Redis schema and data structures
   - Technology stack per service

2. **[Infrastructure Architecture](architecture/infrastructure.md)** (AWS + k3s + networking)
   - VPC topology (public edge, private k3s, NAT instance)
   - k3s cluster setup on EC2
   - EBS CSI driver, storage classes
   - ArgoCD deployment model
   - Terraform module organization

3. **[Architecture Decision Records (ADRs)](architecture/decisions/)** (Technical choices)
   - 15+ ADRs documenting all major decisions
   - Technology selections, cost implications, trade-offs
   - **See [ADR Index](#adr-index) below for quick reference**

---

### 🚀 **Getting Things Done (Runbooks)**

When you need to **perform an operational task**, use these runbooks.

**[Runbooks Execution Order](runbooks/README.md)** — Read this first for the correct sequence.

#### **Bootstrap (First Time Setup)**
- [AWS Account Bootstrap](runbooks/aws-account-bootstrap.md) — Initial AWS account configuration
- [Terraform Backend Bootstrap](runbooks/terraform-backend-bootstrap.md) — Set up Terraform remote state
- [ArgoCD Bootstrap](runbooks/argocd-bootstrap.md) — Deploy ArgoCD to k3s cluster

#### **Operations (Day-to-Day Tasks)**
- [Ingester](runbooks/ingester.md) — Deploy, scale, troubleshoot the ingestion service
- [Processor](runbooks/processor.md) — Deploy, monitor, debug the aggregation service
- [Health Endpoint](runbooks/health-endpoint.md) — k3s health checks via load balancer
- [Admin-Scale API](runbooks/admin-scale.md) — Scale ingester replicas via API
- [Redis](runbooks/redis.md) — Deploy Redis, manage queues and aggregates
- [Observability Stack](runbooks/observability.md) — Prometheus, Grafana, metrics, alerting

#### **Infrastructure & CI/CD**
- [Infrastructure Outputs](runbooks/infra-outputs.md) — Check deployed AWS resources
- [CI/CD for Infrastructure](runbooks/ci-infra.md) — Terraform validate, plan, apply workflows
- [CI/CD for Applications](runbooks/ci-app.md) — Build and push service images

#### **Troubleshooting & Issues**
- [Issue Log](runbooks/issue-log.md) — Known issues, root causes, resolutions
- [Runbooks README](runbooks/README.md) — Detailed execution order and dependencies

---

### 🔐 **Security & Access**

- **[IAM Inventory](iam/inventory.md)** — All IAM users, roles, policies
  - OIDC configuration for GitHub Actions
  - Least-privilege principle applied

---

### 🛠️ **Other Resources**

- **[Project Status & Milestones](project-status.md)** — Feature completion, v1 → v2 roadmap
- **[Dependencies & Sprint Planning](dependencies/sprints-1-3.md)** — Story relationships, sprint goals
- **[Notebook](notebook.md)** — Ad-hoc notes (legacy, may be deprecated)

---

## 📋 ADR Index

Quick reference to all Architecture Decision Records (decisions/):

| ID | Date | Title | Key Decision |
|----|------|-------|--------------|
| [ADR-0001](architecture/decisions/ADR-0001-2026-01-08-aws-region-us-east-1.md) | 2026-01-08 | AWS Region: us-east-1 | Single region, cost-optimized |
| [ADR-0002](architecture/decisions/ADR-0002-2026-01-08-k3s-on-ec2-for-kubernetes.md) | 2026-01-08 | k3s on EC2 for Kubernetes | Lightweight K8s (vs EKS) |
| [ADR-0003](architecture/decisions/ADR-0003-2026-01-08-event-driven-pipeline-redis-buffer.md) | 2026-01-08 | Event-Driven Pipeline + Redis | Async processing, buffering |
| [ADR-0004](architecture/decisions/ADR-0004-2026-01-08-sqlite-storage-with-s3-backups.md) | 2026-01-08 | SQLite Storage + S3 Backups | Lightweight DB, durable backups |
| [ADR-0005](architecture/decisions/ADR-0005-2026-01-08-observability-prometheus-grafana.md) | 2026-01-08 | Observability: Prometheus + Grafana | Metrics collection & visualization |
| [ADR-0007](architecture/decisions/ADR-0007-2026-01-08-edge-cloudfront-nginx.md) | 2026-01-08 | Edge: CloudFront + Nginx | CDN + load balancer |
| [ADR-0008](architecture/decisions/ADR-0008-2026-01-08-vpc-public-edge-private-k3s-nat-instance.md) | 2026-01-08 | VPC: Public Edge, Private k3s, NAT | Network isolation |
| [ADR-0009](architecture/decisions/ADR-0009-2026-01-08-security-baseline-secrets-and-iam.md) | 2026-01-08 | Security Baseline: Secrets & IAM | SSM Parameter Store, least-privilege |
| [ADR-0010](architecture/decisions/ADR-0010-2026-01-08-terraform-remote-state-and-oidc.md) | 2026-01-08 | Terraform Remote State + OIDC | IaC & GitHub Actions integration |
| [ADR-0011](architecture/decisions/ADR-0011-2026-01-16-edge-ssm-vpc-endpoints.md) | 2026-01-16 | Edge: SSM + VPC Endpoints | Secure parameter access |
| [ADR-0012](architecture/decisions/ADR-0012-2026-01-16-edge-s3-gateway-endpoint.md) | 2026-01-16 | Edge: S3 Gateway Endpoint | S3 access without internet |
| [ADR-0014](architecture/decisions/ADR-0014-2026-01-19-processor-language-java.md) | 2026-01-19 | Processor Language: Java | Spring Boot for data processing |
| [ADR-0015](architecture/decisions/ADR-0015-2026-01-22-redis-list-for-ingestion-queue.md) | 2026-01-22 | Redis List for Ingestion Queue | BLPOP-based event consumption |

**⚠️ Note**: ADR-0006 and ADR-0013 are intentionally missing (likely superseded).

---

## 🔗 Cross-References (Common Lookups)

### "How do I deploy X?"
- **Ingester** → See [Application Architecture § 1](architecture/application-architecture.md#1-ingester) + [Runbook: Ingester](runbooks/ingester.md) + [ADR-0015](architecture/decisions/ADR-0015-2026-01-22-redis-list-for-ingestion-queue.md)
- **Processor** → See [Application Architecture § 2](architecture/application-architecture.md#2-processor) + [Runbook: Processor](runbooks/processor.md) + [ADR-0014](architecture/decisions/ADR-0014-2026-01-19-processor-language-java.md)
- **Redis** → See [Runbook: Redis](runbooks/redis.md) + [ADR-0003](architecture/decisions/ADR-0003-2026-01-08-event-driven-pipeline-redis-buffer.md)
- **Prometheus/Grafana** → See [Runbook: Observability](runbooks/observability.md) + [ADR-0005](architecture/decisions/ADR-0005-2026-01-08-observability-prometheus-grafana.md)
- **ArgoCD** → See [Infrastructure Architecture § ArgoCD](architecture/infrastructure.md) + [Runbook: ArgoCD Bootstrap](runbooks/argocd-bootstrap.md)

### "What are the costs?"
- Check [infrastructure.md § Cost Model](architecture/infrastructure.md) for monthly estimates
- Each ADR includes cost implications (search for "$")
- Issues #20, #25, #174 for backup strategy costs

### "How do I scale?"
- [Runbook: Admin-Scale API](runbooks/admin-scale.md) for scaling the ingester
- [Runbook: Scale k3s Cluster](runbooks/admin-scale.md#scaling-k3s) for cluster nodes

### "Something is broken, what do I check?"
- See [Issue Log](runbooks/issue-log.md) for known problems and fixes
- Cross-reference with relevant ADR and runbook

---

## 📊 Documentation Statistics

- **Total .md files**: 45+ (including this hub)
- **ADRs**: 15 (decisions/0001-0015, with gaps 0006, 0013)
- **Runbooks**: 15+ (bootstrap, operations, observability, CI/CD)
- **Architecture docs**: 3 (application, infrastructure, decisions)
- **Supporting docs**: 5+ (IAM, project status, dependencies)

---

## 🔄 Keeping Docs Updated

- **After completing an issue**, update the relevant runbook or ADR
- **After architectural changes**, create or update an ADR
- **After discovering a recurring issue**, add to [Issue Log](runbooks/issue-log.md)
- **When learning something new about deployment**, update the relevant runbook
- See [AGENTS.md § 4.2](../AGENTS.md#42-context--documentation-hygiene) for documentation maintenance rules

---

## 💡 Tips for Navigation

1. **New to CloudRadar?**  
   Start: [Application Architecture](architecture/application-architecture.md) → [Infrastructure Architecture](architecture/infrastructure.md) → [Key ADRs](architecture/decisions/)

2. **Deploying something?**  
   Use: [Runbooks Execution Order](runbooks/README.md) + specific service runbook

3. **Fixing a problem?**  
   Check: [Issue Log](runbooks/issue-log.md) + relevant architecture doc

4. **Making an architectural change?**  
   Review: Relevant [ADR(s)](architecture/decisions/) + [AGENTS.md § 9.1](../AGENTS.md#91-agent-vs-user-responsibilities)

5. **Need context for code?**  
   See: Service-specific README in [src/](../src/) + [Application Architecture](architecture/application-architecture.md)

---

**Last updated**: 2026-01-28  
**Maintained by**: Agent (automated) + User (architectural decisions)
