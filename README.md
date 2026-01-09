# CloudRadar — Flight Telemetry Analyzer (Budget MVP)

> **Event-Driven Telemetry Processing** on AWS & Kubernetes (k3s).

![AWS](https://img.shields.io/badge/AWS-eu--west--1-232F3E?style=flat&logo=amazon-aws&logoColor=white)
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
- Automate build & delivery with **GitHub Actions**
- Provide **observability by design** (Prometheus & Grafana)
- Ensure **data durability** with automated backups

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

## 📊 Observability

Observability is implemented using CNCF-friendly tools:

- **Prometheus** scrapes application and platform metrics
- **Grafana** provides dashboards for:
  - ingestion rate
  - processing latency
  - system health

No managed monitoring services are used in v1.

---

## 🔁 Backup & Restore Strategy

- SQLite data is backed up **daily** using a Kubernetes `CronJob`
- Backups are stored in **Amazon S3**
- A manual backup can be triggered before destroying the environment
- Data is restored automatically on environment rebuild

---

## 🔄 CI/CD — GitHub Actions

The delivery pipeline is fully automated:

1. A DevOps engineer pushes code or opens a pull request
2. **GitHub Actions (hosted runners)** build Docker images
3. Images are published to **GitHub Container Registry (GHCR)**
4. The k3s cluster pulls images and runs updated workloads

No CI/CD components run inside AWS in v1.

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
- Improved security (OIDC, secrets rotation)

---

## 📄 License

MIT License

---

*Project built as part of a DevOps & Cloud Architecture upskilling path.*

## 📚 Decision Records

Architecture decisions are documented in `docs/architecture/decisions/`.
