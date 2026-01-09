# ADR-0008: VPC with Public Edge and Private k3s (NAT Instance)

## Status
Accepted

## Context
We need a cost-optimized network layout for a public edge and private compute.

## Decision
Create a VPC with a **public subnet** for edge (Nginx) and a **private subnet** for k3s nodes, using a **NAT instance** for outbound access.

## Consequences
- Lower cost than a NAT Gateway.
- Requires managing NAT instance lifecycle and hardening.
- Clear separation between public edge and private compute.

## Details
- CIDR: `10.0.0.0/16`
- One public subnet (edge) and one private subnet (k3s nodes)
- Route tables: public subnet routes to IGW; private subnet routes to NAT instance.
- Security groups: Edge (443 public) and Nodes (traffic from Edge + intra-nodes).
- Tags: `Project=CloudRadar`, `Env=dev`.
