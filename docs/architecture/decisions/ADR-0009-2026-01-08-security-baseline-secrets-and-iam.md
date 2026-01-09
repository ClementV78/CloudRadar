# ADR-0009: Security Baseline (Secrets + IAM)

## Status
Accepted

## Context
The project must demonstrate good security hygiene without increasing complexity.

## Decision
- No plaintext secrets in code or state.
- Use AWS SSM Parameter Store or Secrets Manager for runtime secrets.
- Use least-privilege IAM roles/policies and encrypted Terraform state.

## Consequences
- Stronger security posture with minimal overhead.
- Requires initial IAM/OIDC setup for CI and runtime access.
