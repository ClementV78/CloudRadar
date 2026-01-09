# ADR-0002: k3s on EC2 for Kubernetes

## Status
Accepted

## Context
The MVP requires Kubernetes fundamentals without the cost/complexity of a managed control plane.

## Decision
Run **k3s** on **EC2** (one server, one agent) for the MVP.

## Consequences
- Lower cost and simpler control plane setup.
- More operational responsibility (cluster lifecycle, upgrades, backups).
- Architecture remains portable to EKS in a later phase.

## Details
- Install via cloud-init.
- Enable `--secrets-encryption`.
- Prefer SSM access; avoid public SSH.
