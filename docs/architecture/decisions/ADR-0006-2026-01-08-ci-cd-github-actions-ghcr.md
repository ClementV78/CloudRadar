# ADR-0006: CI/CD with GitHub Actions and GHCR

## Status
Accepted

## Context
The MVP requires a lightweight CI/CD pipeline without running CI in AWS.

## Decision
Use **GitHub Actions** for CI/CD and **GitHub Container Registry (GHCR)** for images. Keep infra and app workflows separated.

## Consequences
- No CI infrastructure cost on AWS.
- Consistent, observable pipeline for portfolio demonstration.
- Requires GitHub OIDC/IAM setup for Terraform workflows.

## Details
- Use GitHub OIDC to assume an AWS role for Terraform workflows.
