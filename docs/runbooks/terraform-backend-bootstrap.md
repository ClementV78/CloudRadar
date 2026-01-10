# Terraform Backend Bootstrap (CI)

## Purpose
Create the S3 bucket and DynamoDB table for Terraform state/locking via GitHub Actions,
using a temporary local Terraform state.

## Prerequisites
- AWS account bootstrap completed (OIDC provider + CI role).
- Repo variables set (GitHub → Settings → Secrets and variables → Actions → Variables):
  - `AWS_TERRAFORM_ROLE_ARN`
  - `AWS_REGION` (default for workflow)
  - `TF_LOCK_TABLE_NAME` (default for workflow)

## Run
1) In GitHub Actions, run **bootstrap-terraform-backend** workflow.
2) Provide:
   - `state_bucket_name` (globally unique)
   - `lock_table_name` (prefilled from `TF_LOCK_TABLE_NAME`)
   - `region` (prefilled from `AWS_REGION`)

Example bucket name:
- `cloudradar-tfstate-<account-id>`

### CLI alternative
```bash
gh workflow run bootstrap-terraform-backend \
  --ref main \
  -f region=us-east-1 \
  -f state_bucket_name=cloudradar-tfstate-<account-id> \
  -f lock_table_name=cloudradar-tf-lock
```

## Outputs
- S3 state bucket created with versioning, encryption, public access blocked.
- DynamoDB lock table created (PAY_PER_REQUEST).

## Remote backend configuration (post-bootstrap)
After the backend exists, configure Terraform roots to use it.

CI setup (recommended):
- Store backend identifiers as GitHub Actions variables.
- Initialize Terraform in CI with explicit backend config, for example:
```bash
terraform -chdir=infra/aws/live/dev init \
  -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config="region=$AWS_REGION" \
  -backend-config="dynamodb_table=$TF_LOCK_TABLE_NAME" \
  -backend-config="key=cloudradar/dev/terraform.tfstate"
```

Local setup (optional):
1) Copy the example backend file:
   - `infra/aws/live/dev/backend.hcl.example` → `infra/aws/live/dev/backend.hcl`
   - `infra/aws/live/prod/backend.hcl.example` → `infra/aws/live/prod/backend.hcl`
2) Fill in your real bucket name and region (keep lock table name consistent).
3) Initialize:
```bash
terraform -chdir=infra/aws/live/dev init -backend-config=backend.hcl
```

## Verification
- Confirm S3 bucket exists and has versioning/encryption enabled.
- Confirm DynamoDB table `cloudradar-tf-lock` exists in `us-east-1`.
- Confirm workflow run succeeded in GitHub Actions.

## Notes
- This workflow uses a local backend and does not depend on existing remote state.
- Backend usage is configured in #6 after this completes.

## Related issues
- #33
- #6
