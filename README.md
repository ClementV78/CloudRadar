# CloudRadar — Flight Telemetry Analyzer (Budget MVP)

> **Event-Driven Telemetry Processing** on AWS & Kubernetes (k3s).

![AWS](https://img.shields.io/badge/AWS-us--east--1-232F3E?style=flat&logo=amazon-aws&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-k3s-326CE5?style=flat&logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-IaC-7B42BC?style=flat&logo=terraform&logoColor=white)
![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=flat&logo=github-actions&logoColor=white)

---

## 📌 Project Overview

**CloudRadar** is a **DevOps & Cloud Architecture showcase project** designed to demonstrate how to build an **event-driven data processing platform** on AWS with a strong focus on:

- cost-efficient infrastructure,
- Kubernetes fundamentals,
- automation and CI/CD,
- observability and operational concerns.

The project simulates the ingestion and processing of **flight telemetry data (ADS-B-like events)** and exposes aggregated data through a simple dashboard.

This repository represents **Version 1 (MVP)** of the platform.

---

## 🚀 Getting Started (Runbooks)

Start here if you are setting up the project from scratch.

1. Follow the runbook order in `docs/runbooks/README.md`.
2. Complete each runbook step-by-step (bootstrap → backend → live env).

---

## 🎯 Technical Objectives

- Design a **budget-aware cloud architecture** on AWS
- Run Kubernetes **without managed control plane (EKS)** using **k3s on EC2**
- Implement an **event-driven processing chain** (producer → queue → consumers)
- Automate build & delivery with **GitHub Actions** (planned)
- Provide **observability by design** (Prometheus & Grafana) (planned)
- Ensure **data durability** with automated backups (planned)

---

## 🏗️ High-Level Architecture (v1)

![CloudRadar Architecture](./docs/architecture/cloudradar-v1-high-level.png)

**Key characteristics:**
- AWS Region: **us-east-1**
- Public Edge: **CloudFront + Nginx reverse proxy (EC2)**
- Private compute: **k3s cluster (2 EC2 nodes)**
- Event buffering: **Redis**
- MVP storage: **SQLite (PV / EBS)**
- Observability: **Prometheus & Grafana**
- Backups: **Amazon S3**

---

## ☁️ AWS Infrastructure

The platform runs entirely on AWS with a minimal footprint:

- **EC2 (Public)**  
  - Nginx reverse proxy  
  - HTTPS + Basic Authentication  
- **CloudFront**  
  - Global edge caching for latency optimization  
- **EC2 (Private)**  
  - k3s Server (control plane)  
  - k3s Agent (worker node)  
- **Amazon S3**  
  - Daily backups  
  - Restore on environment rebuild  

Infrastructure is provisioned using **Terraform**, including:
- networking,
- EC2 instances,
- S3 buckets,
- IAM roles and policies.

VPC networking is managed per environment with a reusable module (implemented). See `docs/architecture/infrastructure.md`.

---

## ☸️ Kubernetes Architecture (k3s)

The k3s cluster hosts all application workloads and platform components.

### Namespaces

**apps**
- `ingester` — telemetry ingestion
- `processor` — scalable event consumers
- `dashboard` — API + UI

**data**
- `redis` — event queue / buffer
- `sqlite` — MVP persistent storage

**observability**
- `prometheus`
- `grafana`

---

## 📊 Observability (Planned)

Observability is implemented using CNCF-friendly tools:

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
- Data is restored automatically on environment rebuild

---

## 🔄 CI/CD — GitHub Actions

Infra CI is live; app CI/CD is planned.

1. A DevOps engineer pushes code or opens a pull request
2. **GitHub Actions (hosted runners)** validate infra (fmt/validate/plan + tfsec)
3. Infra changes are applied manually via workflow dispatch (controlled apply)
4. Application pipeline builds Docker images (planned)
5. Images are published to **GitHub Container Registry (GHCR)** (planned)
6. The k3s cluster pulls images and runs updated workloads (planned)

No CI/CD components run inside AWS in v1.

---

## ✅ Implementation Status

**Infra baseline**
1. AWS account secured (MFA root, no static root keys)
2. IAM baseline set (bootstrap user + MFA, bootstrap role, least-privilege policies) — see `docs/runbooks/aws-account-bootstrap.md`
3. IAM OIDC for GitHub Actions configured (no static AWS keys in CI)
4. Terraform backend ready (S3 state + DynamoDB lock)
5. Cost guardrails enabled (budget alerts)
6. Terraform bootstrap solved via a dedicated workflow using local state to create S3/DynamoDB, then remote state for all other stacks
7. VPC module + per-environment live roots (dev/prod) (implemented)

**Automation**
1. Backend bootstrap workflow in GitHub Actions (local state, idempotent)
2. Infra CI workflow (fmt/validate/plan + tfsec) on PRs
3. Manual infra apply workflow (workflow_dispatch)
4. Runbooks available for bootstrap and verification
5. Application CI/CD pipeline (build + GHCR publish) (planned)

**References**
- Runbook: `docs/runbooks/aws-account-bootstrap.md`
- Runbook: `docs/runbooks/terraform-backend-bootstrap.md`
- Runbooks index: `docs/runbooks/README.md`
- Infra doc: `docs/architecture/infrastructure.md`
- Diagram: `docs/architecture/cloudradar-v1-high-level.png`
- Decision Records: `docs/architecture/decisions/`

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
- GitOps workflows (Argo CD / Flux)
- Improved security (secrets rotation)

---

## 📄 License

MIT License

---

*Project built as part of a DevOps & Cloud Architecture upskilling path.*

## 📚 Decision Records

Architecture decisions are documented in `docs/architecture/decisions/`.
