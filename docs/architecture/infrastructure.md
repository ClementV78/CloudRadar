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
  end

  dev --> vpc
  prod --> vpc
```

## VPC baseline (per environment)

```mermaid
flowchart TB
  internet((Internet))

  subgraph VPC["VPC"]
    igw["Internet Gateway"]

    subgraph Public["Public subnet"]
      edge["EC2 Nginx (edge) (planned)"]
      nat["EC2 NAT instance (planned)"]
    end

    subgraph Private["Private subnet"]
      k3s["EC2 k3s nodes (planned)"]
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

## Status

- Implemented: VPC, subnets, route tables, internet gateway.
- Planned: NAT instance route, edge EC2, k3s nodes.

## Notes

- The VPC module is parameterized for multiple environments and can be destroyed cleanly because all core resources live in the module.
- Private subnet egress is optional and only enabled when a NAT instance ID is provided.
- The edge EC2 instance is the public entry point (Nginx reverse proxy) used for TLS termination and basic auth in front of k3s services.
