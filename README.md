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

## 🔄 CI/CD — GitHub Actions (Planned)

The delivery pipeline is planned (app CI/CD is not live yet):

1. A DevOps engineer pushes code or opens a pull request
2. **GitHub Actions (hosted runners)** build Docker images
3. Images are published to **GitHub Container Registry (GHCR)** (planned)
4. The k3s cluster pulls images and runs updated workloads

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

**Automation**
1. Backend bootstrap workflow in GitHub Actions (local state, idempotent)
2. Runbooks available for bootstrap and verification
3. Application CI/CD pipeline (build + GHCR publish) (planned)

**References**
- Runbook: `docs/runbooks/aws-account-bootstrap.md`
- Runbook: `docs/runbooks/terraform-backend-bootstrap.md`
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
