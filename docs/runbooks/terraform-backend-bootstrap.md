# Terraform Backend Bootstrap (CI)

## Purpose
Create the S3 bucket and DynamoDB table for Terraform state/locking via GitHub Actions,
using a temporary local Terraform state.

## Prerequisites
- AWS account bootstrap completed (OIDC provider + CI role).
- Repo variable set: `AWS_TERRAFORM_ROLE_ARN`.

## Run
1) In GitHub Actions, run **bootstrap-terraform-backend** workflow.
2) Provide:
   - `state_bucket_name` (globally unique)
   - `lock_table_name` (default `cloudradar-tf-lock`)
   - `region` (default `us-east-1`)

## Outputs
- S3 state bucket created with versioning, encryption, public access blocked.
- DynamoDB lock table created (PAY_PER_REQUEST).

## Notes
- This workflow uses a local backend and does not depend on existing remote state.
- Backend usage is configured in #6 after this completes.
