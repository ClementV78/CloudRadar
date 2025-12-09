# ✈️ DevOps Flight Telemetry Analyzer
> **Event-Driven Data Processing** on AWS & Kubernetes.

![AWS](https://img.shields.io/badge/AWS-SAA--C03-232F3E?style=flat&logo=amazon-aws&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-KCNA-326CE5?style=flat&logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-IaC-7B42BC?style=flat&logo=terraform&logoColor=white)
![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=flat&logo=github-actions&logoColor=white)

## 📋 Project Overview
Ce projet est une démonstration technique d'une **architecture Cloud Native orientée événements (EDA)**.
Il simule, ingère et analyse des données de télémétrie aérienne (ADS-B) en temps réel, mettant en œuvre les meilleures pratiques **DevOps** et **SRE**.

**Objectifs pédagogiques :**
* Implémenter une chaîne de traitement **Event-Driven** (Producer ➔ Broker ➔ Consumer).
* Provisionner une infrastructure immuable sur **AWS** via **Terraform**.
* Orchestrer les microservices sur **Kubernetes (EKS)**.
* Appliquer les principes **GitOps** pour le déploiement continu.

---

## 🏗 Architecture

### Stack Technique
| Domaine | Technologies | Usage |
| :--- | :--- | :--- |
| **Cloud** | AWS (VPC, EKS, MSK/Kinesis) | Infrastructure managée |
| **IaC** | Terraform | Provisioning modulaire |
| **Orchestration** | Kubernetes | Gestion des conteneurs (Prépa KCNA) |
| **Messaging** | Kafka / RabbitMQ | Bus d'événements (Event-Driven) |
| **Observabilité** | Prometheus & Grafana | Monitoring & Alerting |

### Structure du Repository
L'organisation suit une séparation stricte des préoccupations (SoC) :

```text
/
├── 📂 .github/workflows   # Pipelines CI/CD (Automation)
├── 📂 docs/               # Architecture Decision Records (ADR) & Schémas d'événements
├── 📂 infra/              # Infrastructure as Code (Terraform)
│   ├── modules/           # Modules réutilisables (VPC, EKS, MSK...)
│   └── live/              # Instanciation par environnement (Dev/Prod)
├── 📂 k8s/                # Manifests Kubernetes & Configuration GitOps
│   ├── platform/          # Outillage (Ingress, Monitoring)
│   └── apps/              # Définitions des workloads métiers
└── 📂 src/                # Code des Microservices (Ingester, Processor, Dashboard)
```

---

## 🚀 Getting Started

### Pré-requis
* AWS CLI configuré
* Terraform >= 1.5
* Kubectl & Docker

### Commandes Rapides (Makefile)
```bash
# Initialiser l'infra (Plan)
make infra-plan

# Déployer les manifests K8s locaux
make k8s-apply-dev
```

## 📚 Documentation & ADR
Les décisions architecturales (choix du broker, stratégie de branching, etc.) sont documentées dans le dossier [`docs/adr`](./docs/adr).

---
*Projet réalisé dans le cadre d'une montée en compétence DevOps & Cloud Architecture.*