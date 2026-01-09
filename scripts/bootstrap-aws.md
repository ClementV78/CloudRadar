# AWS Bootstrap Script Spec

## Purpose
Automate AWS account bootstrap steps (Budgets, GitHub OIDC provider, Terraform CI role)
after the account and root security are in place.

## Scope
- Create (or reuse) GitHub OIDC provider in IAM
- Create/update Terraform CI role trust policy
- Attach a minimal inline policy for S3 state + DynamoDB lock creation
- Create monthly budget with email notification (if missing)

## Out of Scope
- Account creation, root MFA, and billing console toggles
- Full landing zone or Organizations setup

## Inputs
- `AWS_REGION` (default `us-east-1`)
- `ROLE_NAME` (default `CloudRadarTerraformRole`)
- `OIDC_PROVIDER_URL` (default `token.actions.githubusercontent.com`)
- `OIDC_PROVIDER_TAG` (default `github-actions-oidc`)
- `REPO_SLUG` (default `ClementV78/CloudRadar`)
- `STATE_BUCKET_PREFIX` (default `cloudradar-tfstate-`)
- `LOCK_TABLE_NAME` (default `cloudradar-tf-lock`)
- `BUDGET_AMOUNT` (default `10`)
- `BUDGET_NAME` (default `cloudradar-monthly-budget`)
- `ALERT_EMAIL` (default `alerts@example.com`)

## Outputs
- `AWS_ACCOUNT_ID`
- `OIDC_PROVIDER_ARN`
- `TERRAFORM_CI_ROLE_ARN`

## Usage
```bash
AWS_REGION=us-east-1 \
ROLE_NAME=CloudRadarTerraformRole \
OIDC_PROVIDER_TAG=github-actions-oidc \
BUDGET_AMOUNT=10 \
ALERT_EMAIL=alerts@example.com \
scripts/bootstrap-aws.sh
```

## Assumptions
- You are already authenticated to AWS CLI with permissions to manage IAM and Budgets.
- The role or user you use is allowed to create OIDC providers and IAM roles.

## Notes
- Do not commit real emails or account identifiers.
- Script is idempotent for OIDC provider and budget checks.
