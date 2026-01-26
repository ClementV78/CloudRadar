# CloudRadar — Flight Telemetry Analyzer (Budget MVP)

> **Event-Driven Telemetry Processing** on AWS & Kubernetes (k3s).

![AWS](https://img.shields.io/badge/Cloud-AWS-232F3E?style=flat&logo=amazon-aws&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-k3s-326CE5?style=flat&logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/IaC-Terraform-7B42BC?style=flat&logo=terraform&logoColor=white)
![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=flat&logo=github-actions&logoColor=white)
![GitOps](https://img.shields.io/badge/GitOps-ArgoCD-FE6A16?style=flat&logo=argo&logoColor=white)
![FinOps](https://img.shields.io/badge/Cost%20Aware-Low%20Budget-2E7D32?style=flat)

---

## 📌 Project Overview

**CloudRadar** is a **DevOps & Cloud Architecture showcase project** designed to demonstrate how to build an **event-driven data processing platform** on AWS with a strong focus on:

- cost-efficient infrastructure,
- Kubernetes fundamentals,
- automation, GitOps, and CI/CD,
- observability and operational concerns.

**Functional Overview:**
The project ingests real-world flight telemetry from OpenSky’s open data stream to power a dashboard that visualizes live aircraft positions and traffic details, lets users define exclusion zones, and triggers alerts on zone intrusions.

**Technical Overview:**
CloudRadar is a Terraform-driven AWS stack (k3s on EC2) with Prometheus/Grafana observability, a React + Leaflet UI, and a GitHub Actions CI/CD pipeline pushing containers to GHCR. Application workloads on k3s are delivered via GitOps with ArgoCD. EC2 nodes use AL2023 minimal AMIs with SSM agent installed explicitly at boot to keep access reliable.

**Project Management:**
All features and fixes are tracked as GitHub Issues, grouped by milestones, and delivered through an iterative, issue-driven workflow. Progress is managed using a GitHub Projects Kanban board, following lightweight agile-inspired practices adapted for a solo project.

This repository represents **Version 1 (MVP)** of the platform.

---

## 🚀 Getting Started (Runbooks)

Start here if you are setting up the project from scratch.

1. Follow the runbook order in [docs/runbooks/README.md](docs/runbooks/README.md).
2. Complete each runbook step-by-step (bootstrap → backend → live env).

---

## 🎯 Technical Objectives

- Design a **budget-aware cloud architecture** on AWS
- Run Kubernetes **without managed control plane (EKS)** using **k3s on EC2**
- Implement an **event-driven processing chain** (producer → queue → consumers)
- Automate infra checks and delivery with **GitHub Actions** (infra CI live; app pipeline planned)
- Provide **observability by design** (Prometheus & Grafana) (planned)
- Ensure **data durability** with automated backups (planned)

---

## 🏗️ High-Level Architecture (v1)

![CloudRadar Architecture](./docs/architecture/cloudradar-v1-high-level.png)

**Key characteristics:**
- AWS Region: **us-east-1**
- Public Edge: **Nginx reverse proxy (EC2)** (dev implemented), **CloudFront** (planned)
- Private compute: **k3s cluster (2 EC2 nodes: 1 control plane + 1 worker)**
- Event buffering: **Redis**
- MVP storage: **SQLite (PV / EBS)**
- Observability (planned): **Prometheus & Grafana**
- Backups (planned): **Amazon S3**

---

## ☁️ AWS Infrastructure

The platform runs entirely on AWS with a minimal footprint:

- **EC2 (Public)**  
  - Nginx reverse proxy (dev implemented, AL2023 minimal + SSM agent install)  
  - HTTPS + Basic Authentication via SSM Parameter Store (dev implemented)  
- **CloudFront**  
  - Global edge caching for latency optimization (planned)  
- **EC2 (Private)**  
  - k3s Server (control plane, AL2023 minimal + SSM agent install)  
  - k3s Agent (worker node, AL2023 minimal + SSM agent install)  
- **NAT Instance**  
  - Egress for private subnets (cost-aware alternative to NAT Gateway)  
- **Amazon S3**  
  - Daily backups (planned)  
  - Restore on environment rebuild  
 - **VPC Endpoints (SSM + S3 gateway)**  
  - Private access for SSM and S3 when enabled (dev implemented)

Infrastructure is provisioned using **Terraform**, including:
- networking,
- EC2 instances,
- S3 buckets,
- IAM roles and policies.

VPC networking is managed per environment with a reusable module (implemented). See `docs/architecture/infrastructure.md` for the network inventory/table.

---

## ☸️ Kubernetes Architecture (k3s)

The k3s cluster hosts all application workloads and platform components.

### Namespaces

**cloudradar**
- `ingester` — telemetry ingestion
- `processor` — event aggregates for the UI
- `admin-scale` — admin API to scale ingester
- `dashboard` — API + UI (planned)

**data**
- `redis` — event queue / buffer
- `sqlite` — MVP persistent storage

**observability**
- `prometheus`
- `grafana`

### GitOps apps (`k8s/apps`)

ArgoCD syncs everything under `k8s/apps` automatically.

- ✅ `health` — minimal `/healthz` endpoint
- ✅ `redis` — event buffer in `data` namespace
- ✅ `ingester` — OpenSky ingestion
- ✅ `processor` — Java processor (Redis aggregates)
- ✅ `admin-scale` — ingester scaling API
- 📝 `dashboard` — API + UI (planned)

---

## 📊 Observability (Planned)

Observability is planned using CNCF-friendly tools:

- **Prometheus** scrapes application and platform metrics
- **Grafana** provides dashboards for:
  - ingestion rate
  - processing latency
  - system health

No managed monitoring services are used in v1.

---

## 🔁 Backup & Restore Strategy (Planned)

- SQLite data is backed up **daily** using a Kubernetes `CronJob`
- Backups are stored in **Amazon S3**
- A manual backup can be triggered before destroying the environment
- Data is restored automatically on environment rebuild (planned)

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

## ✅ Implementation Status

## 📈 Project Progress (Estimates)

| Release | Progress | Estimate |
| --- | --- | --- |
| v1-mvp | `##############------` | 70% |
| v1.1 | `###-----------------` | 15% |
| v2 | `#-------------------` | 5% |

These are high-level estimates based on current scope.

**Progress by category (v1-mvp)**

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

**Next milestones**

**v1.1 (next)**
1. Add logging stack (Loki + Promtail).
2. Add alerting via Alertmanager.
3. Harden networking with baseline NetworkPolicies.
4. Build the custom UI (React/Leaflet) with a polished dashboard experience.

**v2 (next)**
1. Multi-AZ network layout and HA worker nodes.
2. IRSA + least-privilege IAM for workloads.
3. Optional EKS foundation and advanced GitOps.

**References**
- Runbook: [docs/runbooks/aws-account-bootstrap.md](docs/runbooks/aws-account-bootstrap.md)
- Runbook: [docs/runbooks/terraform-backend-bootstrap.md](docs/runbooks/terraform-backend-bootstrap.md)
- Runbooks index: [docs/runbooks/README.md](docs/runbooks/README.md)
- Agent guide (project-specific): [AGENTS.md](AGENTS.md)
- Agent guide (generic template): [docs/agents/GENERIC-AGENTS.md](docs/agents/GENERIC-AGENTS.md)
- Infra doc: [docs/architecture/infrastructure.md](docs/architecture/infrastructure.md)
- Diagram: [docs/architecture/cloudradar-v1-high-level.png](docs/architecture/cloudradar-v1-high-level.png)
- Decision Records: [docs/architecture/decisions/](docs/architecture/decisions/)

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
