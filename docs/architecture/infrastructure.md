# Infrastructure Overview (v1)

This document describes the Terraform layout and the baseline VPC network used per environment.

## Terraform layout

```mermaid
flowchart TB
  subgraph Live["infra/aws/live"]
    dev["dev root"]
    prod["prod root"]
  end

  subgraph Modules["infra/aws/modules"]
    vpc["vpc module"]
    nat["nat-instance module"]
    k3s["k3s module"]
    edge["edge module"]
  end

  dev --> vpc
  dev --> nat
  dev --> k3s
  dev --> edge
  prod --> vpc
  prod --> nat
  prod --> k3s
  prod --> edge
```

## VPC baseline (per environment)

```mermaid
flowchart TB
  internet((Internet))

  subgraph VPC["VPC"]
    igw["Internet Gateway"]

    subgraph Public["Public subnet"]
      edge["EC2 Nginx (edge)"]
      nat["EC2 NAT instance"]
    end

    subgraph Private["Private subnet"]
      k3s["EC2 k3s nodes"]
    end

    publicRT["Public route table"]
    privateRT["Private route table"]

    edgeSG["SG: edge"]
    natSG["SG: nat"]
    k3sSG["SG: k3s"]
  end

  internet --> igw
  igw --> publicRT
  publicRT --> edge
  publicRT --> nat
  privateRT --> k3s
  privateRT -.-> nat

  edgeSG -.-> edge
  natSG -.-> nat
  k3sSG -.-> k3s
```

## Resource inventory (per environment)

### Networking
- VPC, Internet Gateway, public/private subnets, public/private route tables.
- NAT instance (public subnet) with private route table default route.

### Compute
- k3s server EC2 instance (private subnet).
- k3s worker Auto Scaling Group (private subnets) with launch template.
- NAT EC2 instance (public subnet).
- Edge EC2 instance (public subnet).

### Security
- Security group for k3s nodes (explicit ports).
- Security group for NAT (allow from private CIDRs).
- Security group for edge (HTTPS from allowed CIDRs).
- Default VPC network ACLs (no custom NACLs yet).

### IAM
- IAM role + instance profile for k3s nodes (SSM managed policy).
- IAM role + instance profile for edge (SSM managed policy + SSM parameter read).

## Network table (example: dev)

| Component | CIDR / Range | AZ | Route table | Security group | SG name | NACL | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| VPC | 10.0.0.0/16 | us-east-1 | n/a | n/a | n/a | default | `infra/aws/live/dev/terraform.tfvars.example` |
| Public subnet | 10.0.1.0/24 | us-east-1a | public RT | nat SG (NAT), edge SG | `cloudradar-dev-nat`, `cloudradar-dev-edge` | default | Public IPs on launch |
| Private subnet | 10.0.101.0/24 | us-east-1a | private RT | k3s SG | `cloudradar-dev-k3s-nodes` | default | No public IPs |
| Public RT | 0.0.0.0/0 -> IGW | us-east-1 | n/a | n/a | n/a | default | Egress for public subnet |
| Private RT | 0.0.0.0/0 -> NAT | us-east-1 | n/a | n/a | n/a | default | Egress for private subnet |
| k3s SG | 6443/TCP, 10250/TCP, 8472/UDP | us-east-1 | n/a | k3s SG | `cloudradar-dev-k3s-nodes` | default | Self-referenced rules |
| NAT SG | All from private CIDRs | us-east-1 | n/a | nat SG | `cloudradar-dev-nat` | default | Egress to Internet |
| Edge SG | 443/TCP (and 80/TCP redirect) | us-east-1 | n/a | edge SG | `cloudradar-dev-edge` | default | Access limited by `edge_allowed_cidrs` |

## Status

- Implemented (IaC): VPC, subnets, route tables, internet gateway, NAT instance, k3s nodes, edge EC2.
- Planned: observability stack, additional network hardening.

## Notes

- The VPC module is parameterized for multiple environments and can be destroyed cleanly because all core resources live in the module.
- Private subnet egress is handled by the NAT instance module and the private route table default route.
- The edge EC2 instance is the public entry point (Nginx reverse proxy) used for TLS termination and basic auth in front of k3s services.
- Edge basic auth password is read from SSM Parameter Store at boot (see `docs/runbooks/aws-account-bootstrap.md` for IAM).
- TODO: migrate edge TLS to ACM + Route53 (issue #14).
- TODO: tighten edge egress to k3s SG (replace CIDR-based egress).
- IAM permissions needed for these resources are documented in `docs/runbooks/aws-account-bootstrap.md`.
