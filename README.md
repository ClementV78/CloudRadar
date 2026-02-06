# CloudRadar — Flight Telemetry Analyzer (Budget MVP)

> **Queue-Driven Telemetry Processing** on AWS & Kubernetes (k3s).

![AWS](https://img.shields.io/badge/Cloud-AWS-232F3E?style=flat&logo=amazon-aws&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-k3s-326CE5?style=flat&logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/IaC-Terraform-7B42BC?style=flat&logo=terraform&logoColor=white)
![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=flat&logo=github-actions&logoColor=white)
![GitOps](https://img.shields.io/badge/GitOps-ArgoCD-FE6A16?style=flat&logo=argo&logoColor=white)
![FinOps](https://img.shields.io/badge/Cost%20Aware-Low%20Budget-2E7D32?style=flat)

**Key DevOps practices:** IaC (Terraform), GitOps (ArgoCD), CI/CD (GitHub Actions), FinOps (cost-aware infra), Security (OIDC + SSM).

---

## 📌 Project Overview

**CloudRadar** is a **DevOps & Cloud Architecture showcase**: a lightweight AWS platform that runs a **queue-driven telemetry pipeline** (ingester → Redis list → consumers) on a minimal k3s stack. It focuses on cost-efficient infrastructure choices, GitOps delivery, and operational readiness.

**Functional Overview:** Ingest live flight telemetry from OpenSky, aggregate events, and expose data for a map dashboard with alertable zones.

**Technical Overview:** Terraform provisions AWS (k3s on EC2, IAM, S3, VPC). GitHub Actions runs infra CI; ArgoCD syncs `k8s/apps`. Prometheus/Grafana observability is implemented (GitOps-provisioned dashboards).

**Project Management:**
Issues and PRs are tracked in GitHub Projects (Kanban board) using lightweight agile practices adapted for solo development. Templates enforce metadata standards (assignees, labels, milestones, project), and an automated workflow ensures consistency and quality across all tickets and PRs.

**Why this project exists:** provide a concrete, end-to-end DevOps/Cloud Architecture portfolio with real IaC, CI/CD, GitOps, and cost trade-offs.

**Quick links:** [📚 Documentation Hub](docs/README.md) · [🚀 Runbooks](docs/runbooks/README.md) · [🏗️ Infra](docs/architecture/infrastructure.md) · [🎯 ADRs](docs/architecture/decisions/)

## 🛠️ DevOps Tooling & Practices

| Tool / Practice | Purpose | Value | Status |
| --- | --- | --- | --- |
| Terraform | Infrastructure as Code for AWS resources (VPC, EC2, IAM, S3) | Reproducible, reviewable infrastructure changes | Implemented |
| GitHub Actions | CI for infra (`fmt/validate/plan`) + multi-image builds | Fast feedback and safer changes; parallel service builds | Implemented |
| GitHub Container Registry (GHCR) | Container registry for app images | Centralized, versioned image distribution | Implemented (automated multi-image: ingester, processor, health, admin-scale) |
| Docker | Local image builds for services | Portable builds aligned with CI artifacts | Implemented (local/manual) |
| ArgoCD (GitOps) | Sync `k8s/apps` to the cluster | Declarative deploys and drift control | Implemented |
| Kustomize | Compose Kubernetes manifests | Reuse and consistency across apps | Implemented |
| Helm | Manage charts via ArgoCD (e.g., EBS CSI) | Standard packaging for add-ons | Implemented (via ArgoCD) |
| k3s | Lightweight Kubernetes on EC2 | Low-cost, production-like K8s | Implemented |
| Prometheus + Grafana | Metrics collection and dashboards | Monitoring & observability by design | Implemented (7d retention, 5GB PVC, ~$0.50/month, auto-deployed via ArgoCD) |
| SSM Parameter Store + IAM OIDC | Secure parameters and CI access | Fewer static creds, better auditability | Implemented |

## 🧩 DevOps Skills Demonstrated

| Skill | Evidence in this project |
| --- | --- |
| Infrastructure as Code | Terraform modules, remote state (S3 + DynamoDB) |
| GitOps Delivery | ArgoCD syncs `k8s/apps` to the cluster |
| CI for Infrastructure | GitHub Actions `fmt/validate/plan` with manual apply |
| Secure CI Access | IAM OIDC (no static AWS keys) |
| Cost Awareness | k3s on EC2 + NAT instance vs managed alternatives |
| Operational Readiness | Runbooks for bootstrap, verification, and ops |

This repository represents **Version 1 (MVP)** of the platform.

---

## 🎯 Technical Objectives

- Design a **budget-aware cloud architecture** on AWS
- Run Kubernetes **without managed control plane (EKS)** using **k3s on EC2**
- Implement a **queue-driven processing chain** (producer → Redis queue → consumers)
- Automate infra checks and delivery with **GitHub Actions** (infra CI live; app pipeline planned)

---

## 🏗️ High-Level Architecture (v1)

![CloudRadar Architecture](./docs/architecture/cloudradar-v1-high-level.png)

**Key characteristics:**
- AWS Region: **us-east-1**
- Public Edge: **Nginx reverse proxy (EC2)** (dev implemented), **CloudFront** (planned)
- Private compute: **k3s cluster (2 EC2 nodes: 1 control plane + 1 worker)**
- Event buffering: **Redis**
- MVP storage: **SQLite (PV / EBS)**
- Observability: **Prometheus + Grafana** (7d retention, $0.50/month)
- Backups (planned): **Daily SQLite to Amazon S3**

---

## ☁️ AWS Infrastructure

High-level components (details in [docs/architecture/infrastructure.md](docs/architecture/infrastructure.md)):

| Component | Role | Status |
| --- | --- | --- |
| VPC (public edge + private k3s) | Network segmentation and routing | Implemented |
| EC2 (Public) | Nginx reverse proxy + basic auth | Dev implemented |
| EC2 (Private) | k3s server + worker nodes | Implemented |
| NAT Instance | Private subnet egress | Implemented |
| CloudFront | Edge caching | Planned |
| S3 | Backups (daily) | Planned |
| VPC Endpoints (SSM, S3) | Private access to control/data plane | Dev implemented |

Infrastructure is provisioned with Terraform (networking, IAM, compute, storage).

---

## 🔄 CI/CD — GitHub Actions

Infra CI is live; app CI/CD is planned. Application workloads on k3s are reconciled by ArgoCD (GitOps) from `k8s/apps`.

1. A DevOps engineer pushes code or opens a pull request
2. **GitHub Actions (hosted runners)** validate infra (fmt/validate/plan + tfsec)
3. Infra changes are applied manually via workflow dispatch (controlled apply)
4. Application pipeline builds Docker images (planned)
5. Images are published to **GitHub Container Registry (GHCR)** (planned)
6. ArgoCD syncs `k8s/apps` manifests to the k3s cluster (implemented)
7. The k3s cluster pulls images and runs updated workloads (planned)

---

## 🧪 CI Guardrails (K8s)

To prevent deployment regressions, a dedicated workflow validates Kubernetes manifests on every PR that touches `k8s/**`.

- Workflow: `ci-k8s`
- Check: fail if any `ghcr.io/...` image reference contains uppercase characters (GHCR requires lowercase repository names).

Status: Implemented.

---

## ☸️ Kubernetes Architecture (k3s)

The k3s cluster runs a **control plane** and **worker node(s)** on EC2, hosting all application workloads and platform components. ArgoCD manages workloads declaratively via GitOps.

### GitOps apps (`k8s/apps`)

ArgoCD syncs everything under `k8s/apps` automatically.

| App | Role | Namespace | Status |
| --- | --- | --- | --- |
| `health` | Minimal `/healthz` endpoint + `/readyz` probe | `cloudradar` | Implemented |
| `redis` | Event buffer | `data` | Implemented |
| `ingester` | OpenSky ingestion | `cloudradar` | Implemented |
| `processor` | Redis aggregates | `cloudradar` | Implemented |
| `admin-scale` | Ingester scaling API | `cloudradar` | Implemented |
| `dashboard` | API + UI | `cloudradar` | Planned |

---

## 📈 Project Progress (Estimates)

| Release | Progress | Estimate |
| --- | --- | --- |
| v1-mvp | `##############------` | 70% |
| v1.1 | `###-----------------` | 15% |
| v2 | `#-------------------` | 5% |

These are high-level estimates based on current scope.
Detailed status is tracked in [docs/project-status.md](docs/project-status.md) and in the GitHub Project board.

| Category | Progress | Notes |
| --- | --- | --- |
| Infra | ✅ Mostly done | k3s nodes, edge, IAM, Terraform backend |
| Automation | ✅ Core done | infra CI + manual apply; app CI/CD in progress |
| Application | 📝 In progress | ingestion → Redis → processor working; storage/API pending |
| Monitoring | ✅ Implemented | Prometheus/Grafana (MVP), AlertManager (planned Sprint 2) |
| UI | 📝 Planned | Grafana Geomap MVP |

---

## � Where to Find Information

**Documentation is organized in a hub model:**

### If you want to...

| Goal | Start Here |
| --- | --- |
| **Understand the architecture** | [📚 Documentation Hub → Architecture](docs/README.md#️-understanding-the-architecture) |
| **Deploy or operate something** | [🚀 Runbooks Execution Order](docs/runbooks/README.md) |
| **Find a specific runbook** | [📚 Documentation Hub → Runbooks](docs/README.md#-getting-things-done-runbooks) |
| **Check a technical decision** | [🎯 ADRs Index](docs/README.md#-adr-index) |
| **Troubleshoot an issue** | [🚧 Issue Log](docs/runbooks/troubleshooting/issue-log.md) + [🎯 ADRs](docs/architecture/decisions/) |
| **Understand k3s/infra** | [🏗️ Infrastructure Architecture](docs/architecture/infrastructure.md) |
| **Understand microservices** | [🧩 Application Architecture](docs/architecture/application-architecture.md) |
| **Review agent rules** | [AGENTS.md](AGENTS.md) (root) |

**→ [Go to the Documentation Hub](docs/README.md) for the complete navigation guide.**

---

## �🚀 Getting Started (Runbooks)

Start here if you are setting up the project from scratch.

1. Follow the runbook order in [docs/runbooks/README.md](docs/runbooks/README.md).
2. Complete each runbook step-by-step (bootstrap → backend → live env).

---

## 🌿 Branching & Environments

- `main` is the single source of truth (not tied to a specific environment).
- Environment promotion uses IaC variables or `infra/live/*` layouts, not long-lived branches.

---

## ✅ Commit Conventions

- Use `type(scope): message` (same as issues).
- Link commits to issues with `Refs #<issue>` or `Fixes #<issue>`.

---

## 🚧 Roadmap

Planned future evolutions:

- Migration from SQLite to **managed database (RDS)**
- Advanced autoscaling scenarios (HPA, event-based scaling)
- GitOps evolution (ArgoCD app expansion, health sync) — Flux optional (planned)
- Improved security (secrets rotation)

---

## 📄 License

MIT License

---

*Project built as part of a DevOps & Cloud Architecture upskilling path.*
