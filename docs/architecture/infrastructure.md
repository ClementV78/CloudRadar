# Infrastructure Overview (v1)

This document describes the Terraform layout and the baseline VPC network used per environment.

## Terraform layout

### Terraform roots
- `infra/aws/bootstrap`: creates the Terraform backend (state bucket, lock table) and the SQLite backup bucket.
- `infra/aws/live/dev`: deploys the full dev stack (VPC, NAT instance, k3s, edge) and wires the backup bucket name to k3s.
- `infra/aws/live/prod`: deploys the prod VPC baseline only (future app/edge/k3s TBD).

```mermaid
flowchart TB
  subgraph Bootstrap["infra/aws/bootstrap"]
    backend["state S3 bucket"]
    lock["DynamoDB lock table"]
    backups["SQLite backup bucket"]
  end

  subgraph Live["infra/aws/live"]
    dev["dev root"]
    prod["prod root"]
  end

  subgraph Modules["infra/aws/modules"]
    subgraph vpc["vpc module"]
      vpc_res["VPC"]
      subnets["Public/private subnets"]
      rts["Route tables"]
      igw["Internet gateway"]
    end

    subgraph nat["nat-instance module"]
      nat_ec2["NAT EC2 instance"]
      nat_eip["Elastic IP"]
      nat_route["Private RT default route"]
    end

    subgraph k3s["k3s module"]
      k3s_server["k3s server EC2"]
      k3s_workers["k3s worker ASG"]
      k3s_sg["Security groups"]
      k3s_iam["IAM role/profile"]
    end

    subgraph edge["edge module"]
      edge_ec2["Edge EC2 instance"]
      edge_sg["Security group"]
      edge_ssm["SSM parameter (basic auth)"]
    end

    subgraph backup["backup-bucket module"]
      backup_bucket["S3 bucket (versioning + SSE + public access block)"]
    end
  end

  dev --> vpc
  dev --> nat
  dev --> k3s
  dev --> edge
  prod --> vpc
  Bootstrap --> backup
```

## VPC diagram (SGs on network flows)

```mermaid
flowchart TB
  internet((Internet))
  s3((S3))

  subgraph VPC["VPC"]
    direction TB
    igw["Internet Gateway"]

    subgraph Public["Public subnet"]
      direction TB
      publicRT["Public route table"]
      edge["EC2 Nginx (edge)"]
      nat["EC2 NAT instance"]
    end

    subgraph Private["Private subnet"]
      direction TB
      k3s["EC2 k3s nodes"]
      privateRT["Private route table"]
      ssmEndpoints["SSM/KMS interface endpoints (optional)"]
    end

    s3Gateway["S3 gateway endpoint"]
  end

  internet --> igw
  igw --> publicRT
  publicRT --> edge
  publicRT --> nat
  edge -. "nodeports to k3s" .-> k3s
  k3s --- privateRT
  privateRT -.-|0.0.0.0/0 egress| nat
  publicRT -. "S3 route" .-> s3Gateway
  privateRT -. "S3 route" .-> s3Gateway
  s3Gateway -.-> s3

  edge -. "SSM (optional)" .-> ssmEndpoints
  k3s -. "SSM (optional)" .-> ssmEndpoints
```

## Kubernetes workloads (namespaces + data flow)

```mermaid
flowchart LR
  subgraph Data["data namespace"]
    direction TB
    redis["Redis (buffer)"]
    sqlite["SQLite (PVC)"]
  end

  subgraph Apps["apps namespace"]
    direction TB
    ingester["Ingester"]
    processor["Processor"]
    dashboard["Dashboard"]
  end

  subgraph Obs["observability namespace"]
    direction TB
    prom["Prometheus"]
    graf["Grafana"]
  end

  opensky["OpenSky API"]

  ingester -- "0. fetch" --> opensky
  ingester -- "1. ingest" --> redis
  processor -- "2. consume" --> redis
  processor -- "3. persist" --> sqlite

  prom -. "4. scrape" .-> ingester
  prom -. "5. scrape" .-> processor
  graf -- "6. query" --> prom
```

## Resource inventory (per environment)

### Networking
- VPC, Internet Gateway, public/private subnets, public/private route tables.
- NAT instance (public subnet) with private route table default route.
- Interface VPC endpoints for SSM/KMS services.
- Gateway VPC endpoint for S3 (public + private route tables).

### Compute
- k3s server EC2 instance (private subnet, AL2023 minimal AMI with SSM agent installed via cloud-init).
- k3s worker Auto Scaling Group (private subnets) with launch template (AL2023 minimal AMI with SSM agent installed via cloud-init).
- NAT EC2 instance (public subnet).
- Edge EC2 instance (public subnet, AL2023 minimal AMI with SSM agent installed via user-data).

### EC2 instance types (defaults)

Values come from `infra/aws/live/dev/terraform.tfvars` unless overridden per environment.

| Component | Default instance type | Notes |
| --- | --- | --- |
| k3s server | `t3.small` | Private subnet (dev). |
| k3s workers | `t3a.medium` | Auto Scaling Group (dev). |
| edge | `t3.micro` | Public subnet (dev). |
| NAT instance | `t3.nano` | Public subnet (dev). |

Prod values are currently aligned with module defaults and may be overridden later in `infra/aws/live/prod`.
- ArgoCD deployed on k3s (bootstrapped via SSM from CI).

### Storage
- EBS CSI driver deployed via ArgoCD (kube-system).
- `ebs-gp3` StorageClass for stateful workloads.
- S3 backup bucket for SQLite snapshots.

### Security
- Security group for k3s nodes (explicit ports).
- Security group for NAT (allow from private CIDRs).
- Security group for edge (HTTPS from allowed CIDRs).
- Default VPC network ACLs (no custom NACLs yet).
  - k3s node SG rules are self-referenced for 6443/10250/8472 (node-to-node only).
  - Edge reaches k3s only through NodePorts (30080/30081) via SG edge -> SG k3s.
  - Edge may allow HTTPS egress for SSM when endpoints are disabled (dev only).
  - Without SSM endpoints, Session Manager access requires `ssm:StartSession` on `arn:aws:ssm:us-east-1:<account-id>:document/SSM-SessionManagerRunShell`.

### IAM
- IAM role + instance profile for k3s nodes (SSM managed policy).
- IAM managed policy attachment for EBS CSI driver (k3s nodes).
- IAM policy for k3s nodes to read/write SQLite backups in S3.
- IAM role + instance profile for edge (SSM managed policy + SSM parameter read).
- Full IAM inventory: `docs/iam/inventory.md`.

## Network table (example: dev)

| Component | CIDR / Range | AZ | Route table | Security group | SG name | NACL | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| VPC | 10.0.0.0/16 | us-east-1 | n/a | n/a | n/a | default | `infra/aws/live/dev/terraform.tfvars` |
| Public subnet | 10.0.1.0/24 | us-east-1a | public RT | nat SG (NAT), edge SG | `cloudradar-dev-nat`, `cloudradar-dev-edge` | default | Public IPs on launch |
| Private subnet | 10.0.101.0/24 | us-east-1a | private RT | k3s SG | `cloudradar-dev-k3s-nodes` | default | No public IPs |
| Public RT | 0.0.0.0/0 -> IGW | us-east-1 | n/a | n/a | n/a | default | Egress for public subnet |
| Private RT | 0.0.0.0/0 -> NAT | us-east-1 | n/a | n/a | n/a | default | Egress for private subnet |
| k3s SG | 6443/TCP, 10250/TCP, 8472/UDP | us-east-1 | n/a | k3s SG | `cloudradar-dev-k3s-nodes` | default | Self-referenced rules |
| k3s SG (Ingress) | 80/TCP, 443/TCP from edge | us-east-1 | n/a | edge SG | `cloudradar-dev-k3s-nodes` | default | Allow edge to k3s Ingress Controller |
| NAT SG | All from private CIDRs | us-east-1 | n/a | nat SG | `cloudradar-dev-nat` | default | Egress to Internet |
| Edge SG | 443/TCP (and 80/TCP redirect) | us-east-1 | n/a | edge SG | `cloudradar-dev-edge` | default | Access limited by `edge_allowed_cidrs` |
| SSM endpoints | 443/TCP from edge SG | us-east-1 | n/a | edge SSM endpoints SG | `cloudradar-dev-edge-ssm-endpoints` | default | Interface endpoints for SSM/KMS |
| S3 endpoint | AWS prefix list | us-east-1 | public + private RT | edge SG (egress) | `cloudradar-dev-s3-endpoint` | default | Gateway endpoint for AL2023 repos |

## Observability Stack (Prometheus + Grafana)

| Component | Details | Status |
| --- | --- | --- |
| **Namespace** | `monitoring` | Created by Terraform/K8s (manual) |
| **Prometheus** | `prometheus-community/kube-prometheus-stack` v60.0.2 | Deployed via ArgoCD Application |
| **Grafana** | `grafana/grafana` v7.6.10 | Deployed via ArgoCD Application |
| **Storage** | 5GB gp3 EBS (Prometheus PVC) | Provisioned via EBS CSI + gp3 StorageClass |
| **Retention** | 7 days (or when 4GB limit hit) | Configurable in Prometheus Helm values |
| **Datasource** | Prometheus → Grafana (internal DNS) | Auto-configured, cluster-local |
| **Ingress** | Traefik ingress controller (k3s default) | `grafana.cloudradar.local` + `prometheus.cloudradar.local` via edge (host rewrite) |
| **Authentication** | K8s Secret `grafana-admin` (password) | Created manually post-bootstrap (see runbook) |
| **Password Storage** | AWS SSM Parameter Store | `/cloudradar/grafana/admin-password` |
| **Cost** | ~$0.50/month (PVC only) | 5GB gp3 @ $0.10/GB/month |
| **Metrics Targets** | node-exporter, kube-state-metrics, app pods (with scrape labels) | Auto-discovered via ServiceMonitor |

**Deployment Flow:**
1. Terraform generates passwords + stores in SSM (no K8s create)
2. Manual: create K8s Secrets in monitoring namespace (post-bootstrap)
3. ArgoCD detects `k8s/apps/monitoring/` and syncs Applications
4. Prometheus starts, scrapes metrics; Grafana connects as datasource
5. Edge Nginx routes `/grafana` and `/prometheus` to k3s Ingress and rewrites Host to `grafana.cloudradar.local` / `prometheus.cloudradar.local`

See [docs/runbooks/observability.md](../runbooks/observability.md) for operational details and setup steps.

## Status

- Implemented (IaC): VPC, subnets, route tables, internet gateway, NAT instance, k3s nodes, edge EC2, S3 backup bucket for SQLite snapshots.
- Implemented (IaC, dev): SSM/KMS interface endpoints are temporarily disabled to reduce cost; edge uses HTTPS egress for SSM.
- Implemented (Platform): ArgoCD bootstrap via SSM/CI for GitOps delivery, Redis buffer in the data namespace, EBS CSI driver + `ebs-gp3` StorageClass.
- Implemented (Observability): Prometheus + Grafana stack (7d retention, 5GB PVC, $0.50/month), auto-deployed via ArgoCD, manual K8s Secret setup.
- Planned: additional network hardening, AlertManager rules (Sprint 2).

## Notes

- The VPC module is parameterized for multiple environments and can be destroyed cleanly because all core resources live in the module.
- Private subnet egress is handled by the NAT instance module and the private route table default route.
- The edge EC2 instance is the public entry point (Nginx reverse proxy) used for TLS termination and basic auth in front of k3s services.
- Edge basic auth password is read from SSM Parameter Store at boot (see `docs/runbooks/aws-account-bootstrap.md` for IAM).
- Edge SSM access is routed via VPC interface endpoints when enabled; otherwise, edge allows HTTPS egress for SSM over the Internet.
- Edge depends on the SSM/S3 endpoints being created first to avoid cloud-init timeouts during bootstrap.
- ArgoCD is bootstrapped via SSM from CI after infrastructure apply, then manages k8s apps via GitOps.
- SSM agent is installed explicitly in k3s/edge bootstrap to avoid AMI package drift.
- Edge package installs use the S3 gateway endpoint plus SG egress to the S3 prefix list.
- SQLite backup bucket is provisioned by the Terraform bootstrap stack to keep it outside environment destroys.
- TODO: set edge `server_name` to the public DNS name once available (remove nginx warning).
- TODO: improve edge HA by running nginx on public k3s nodes + ASG (min 1, ideally 2+) with vertical scaling as needed.
- TODO: migrate edge TLS to ACM + Route53 (issue #14).
- TODO: tighten edge egress to k3s SG (replace CIDR-based egress).
- IAM permissions needed for these resources are documented in `docs/runbooks/aws-account-bootstrap.md`.
