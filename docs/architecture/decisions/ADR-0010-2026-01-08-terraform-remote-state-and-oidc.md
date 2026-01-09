# ADR-0010: Terraform Remote State and OIDC

## Status
Accepted

## Context
We need a secure, team-ready Terraform workflow with state locking and CI auth.

## Decision
Use an S3 backend with DynamoDB locking and GitHub OIDC for Terraform auth.
Bootstrap the backend via GitHub Actions using a temporary local Terraform state.

## Consequences
- Prevents state drift and concurrent apply issues.
- Enables CI without static AWS credentials.
- Introduces a two-step flow: bootstrap backend first, then standard Terraform workflows.
- Requires a CI workflow with narrowly scoped permissions for backend creation.

## Details
- S3 bucket: `cloudradar-tfstate-<unique>` (versioned, encrypted, public access blocked).
- DynamoDB lock table: `cloudradar-tf-lock` (on-demand).
- OIDC provider: `token.actions.githubusercontent.com` with repo-restricted trust policy.
- CI role name: `CloudRadarTerraformRole`.
- OIDC provider tag/name: `github-actions-oidc`.
- Backend bootstrap runs in CI with a local state file (ephemeral) to create S3/DynamoDB.
- Bootstrap workflow runs `terraform init` with a **local** backend in a dedicated folder (or with `-backend=false`) to avoid dependency on the remote backend.
- After bootstrap, standard Terraform workflows use the S3/DynamoDB backend exclusively.

## Rationale
- Keeps the process fully automated (portfolio-friendly) while avoiding manual AWS console steps.
- Avoids chicken-and-egg issues by using a short-lived local state only for backend creation.
- Preserves security posture by relying on OIDC and least-privilege IAM roles.

## Implementation Notes
- The bootstrap workflow should be idempotent and non-destructive (no deletes).
- Backend identifiers (bucket name, table name, region) should be stored as repo variables or documented in a backend config file.

## Related issues
- #32
- #33
- #6
