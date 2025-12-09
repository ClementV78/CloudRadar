# ✈️ DevOps Flight Telemetry Analyzer

> **Event-Driven Data Processing** on AWS & Kubernetes.

![AWS](https://img.shields.io/badge/AWS-SAA--C03-232F3E?style=flat&logo=amazon-aws&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-KCNA-326CE5?style=flat&logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-IaC-7B42BC?style=flat&logo=terraform&logoColor=white)
![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=flat&logo=github-actions&logoColor=white)

## 📋 Project Overview

This project is a technical showcase of a **Cloud Native Event-Driven Architecture (EDA)**.
It simulates, ingests, and analyzes real-time flight telemetry data (ADS-B), demonstrating **DevOps** best practices, **Infrastructure as Code**, and **GitOps** workflows.

**Key Technical Objectives:**
* **Event-Driven Design:** Implement a scalable processing chain (Producer ➔ Broker ➔ Consumer).
* **Infrastructure as Code:** Provision immutable infrastructure on **AWS** using modular **Terraform**.
* **Container Orchestration:** Manage microservices on **Kubernetes (EKS)** (KCNA aligned).
* **Automation:** Establish a robust CI/CD pipeline using **GitHub Actions** and **GitOps** principles.

---

## 🏗 Architecture

### Tech Stack

| Domain | Technology | Usage |
| :--- | :--- | :--- |
| **Cloud Provider** | AWS (VPC, EKS, MSK/Kinesis) | Managed Infrastructure |
| **IaC** | Terraform | Modular Provisioning & State Management |
| **Orchestration** | Kubernetes | Container Management & Scheduling |
| **Messaging** | Kafka / RabbitMQ | Event Bus (Decoupled Architecture) |
| **Observability** | Prometheus & Grafana | Monitoring, Logging & Alerting (PLG) |
| **CI/CD** | GitHub Actions | Automation & Workflows |

### Repository Structure

This repository follows a strict **Separation of Concerns (SoC)** between application code, infrastructure, and platform configuration:

```text
/
├── 📂 .github/workflows   # CI/CD Pipelines (Automation)
├── 📂 docs/               # Architecture Decision Records (ADR) & Event Schemas
├── 📂 infra/              # Infrastructure as Code (Terraform)
│   ├── modules/           # Reusable Infrastructure Modules (VPC, EKS, MSK...)
│   └── live/              # Environment Instantiation (Dev/Prod)
├── 📂 k8s/                # Kubernetes Manifests & GitOps Config
│   ├── platform/          # System Components (Ingress, Monitoring, Cert-Manager)
│   └── apps/              # Business Workloads definitions
└── 📂 src/                # Microservices Source Code (Ingester, Processor, Dashboard)
```

---

## 🚀 Getting Started

### Prerequisites

* **AWS CLI** configured with appropriate IAM permissions.
* **Terraform** (v1.5+).
* **Kubectl** & **Docker** installed.

### Quick Start (Makefile)

This project uses a `Makefile` to standardize development and deployment commands.

```bash
# Initialize Terraform and check the plan
make infra-plan

# Deploy local Kubernetes manifests (Dev overlay)
make k8s-apply-dev

# Build and run microservices locally
make run-local
```

## 📚 Documentation & ADRs

All major architectural decisions (e.g., Message Broker selection, Branching strategy) are documented in the [`docs/adr`](./docs/adr) directory following the ADR standard.

---

*Project created as part of a DevOps & Cloud Architecture upskilling path.*