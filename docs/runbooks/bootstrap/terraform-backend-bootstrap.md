# Terraform Backend Bootstrap (CI)

## Purpose
Create the S3 bucket and DynamoDB table for Terraform state/locking via GitHub Actions,
using a temporary local Terraform state.

Optionally, it can also create:
- an S3 bucket for SQLite backups
- an S3 bucket for aircraft reference data artifacts
- a Route53 hosted zone for DNS delegation

When requested (`issue_tls=true`), it can also:
- issue a public certificate with Let's Encrypt DNS-01
- store certificate artifacts in AWS SSM Parameter Store (`SecureString`)

## Architecture (Bootstrap Stack)

```mermaid
flowchart TB
  subgraph GH["GitHub Actions"]
    WF["Workflow: bootstrap-terraform-backend"]
    TF["Terraform root: infra/aws/bootstrap\n(local state on runner)"]
    WF --> TF
  end

  subgraph AWS["AWS"]
    ROLE["OIDC IAM role\n(AWS_BOOTSTRAP_ROLE_ARN)"]

    S3["S3 bucket (Terraform state)"]
    DDB["DynamoDB lock table\n(lock_table_name / TF_LOCK_TABLE_NAME)"]

    BK["S3 bucket (SQLite backups, optional)"]
    AR["S3 bucket (Aircraft reference data, optional)"]
    R53["Route53 hosted zone (optional)"]
    SSM["SSM Parameters (optional TLS artifacts)\n/cloudradar/edge/tls/*"]
  end

  WF -->|assume role via OIDC| ROLE
  TF --> S3
  TF --> DDB
  TF --> BK
  TF --> AR
  TF --> R53
  WF -->|issue cert (optional)| R53
  WF -->|store fullchain+key (optional)| SSM
```

## Prerequisites
- AWS account bootstrap completed (OIDC provider + CI role).
- Repo variables set (GitHub → Settings → Secrets and variables → Actions → Variables):
  - `AWS_BOOTSTRAP_ROLE_ARN` (used by `bootstrap-terraform-backend`)
  - `AWS_TERRAFORM_ROLE_ARN` (used by `ci-infra`)
  - `AWS_REGION`
  - `TF_STATE_BUCKET`
  - `TF_LOCK_TABLE_NAME`
  - `TF_BACKUP_BUCKET_NAME` (optional, SQLite backups bucket)
  - `TF_AIRCRAFT_REFERENCE_BUCKET_NAME` (optional, aircraft reference bucket)
  - `DNS_ZONE_NAME` (optional unless `issue_tls=true`)
  - `TLS_DOMAIN` (required when `issue_tls=true`)

## Run
1) In GitHub Actions, run **bootstrap-terraform-backend** workflow.
2) Provide only:
   - `issue_tls` (checkbox; default false)

Important behavior:
- If `issue_tls=false`, the workflow validates that a valid existing certificate is already present in SSM (`/cloudradar/edge/tls/fullchain_pem` + `/cloudradar/edge/tls/privkey_pem`).
- If no valid existing certificate is found, the workflow fails fast.
- If `issue_tls=true`, `TLS_DOMAIN` and `DNS_ZONE_NAME` variables must be set and consistent.

Example bucket name:
- `cloudradar-tfstate-<account-id>`

### CLI alternative
```bash
gh workflow run bootstrap-terraform-backend \
  --ref main \
  -f issue_tls=true
```

## Outputs
- S3 state bucket created with versioning, encryption, public access blocked.
- DynamoDB lock table created (PAY_PER_REQUEST).
- SQLite backup bucket created (if `backup_bucket_name` provided).
- Aircraft reference bucket created (if `aircraft_reference_bucket_name` provided).
- Route53 hosted zone created (if `dns_zone_name` provided).
- TLS artifacts stored in SSM (if `issue_tls=true`):
  - `/cloudradar/edge/tls/fullchain_pem` (`SecureString`)
  - `/cloudradar/edge/tls/privkey_pem` (`SecureString`)
  - `/cloudradar/edge/tls/metadata` (`String`, non-sensitive metadata)

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
- If provided: confirm SQLite backup bucket exists.
- If provided: confirm aircraft reference bucket exists.
- If provided: confirm Route53 hosted zone exists and name servers are returned in outputs.
- If `issue_tls=true`: confirm TLS parameters exist in SSM.
- Confirm workflow run succeeded in GitHub Actions.

## Notes
- This workflow uses a local backend and does not depend on existing remote state.
- The workflow imports existing resources (state bucket, lock table, optional backup bucket, optional aircraft reference bucket, optional hosted zone) when they already exist, so it can be rerun safely.
- Remote backend usage is configured in CI (see example `terraform init` commands above) and optionally locally via `backend.hcl`.
- TLS issuance details:
  - Uses Let's Encrypt DNS-01 challenge through Route53.
  - Forces RSA key generation (`--key-type rsa --rsa-key-size 2048`) for edge compatibility.
  - `TLS_DOMAIN` must be inside `DNS_ZONE_NAME`.
  - SSM writes use `SecureString` Standard tier first, then Advanced tier fallback only if value size exceeds Standard limits.

## State Persistence (Recommended)
This workflow intentionally uses a local Terraform backend on the GitHub Actions runner to avoid a chicken-and-egg dependency.
However, the runner state file is ephemeral.

To avoid losing the bootstrap Terraform state, migrate it to the remote backend after the bootstrap resources exist.

Example (local):
1) Import existing resources (if needed), then migrate:
```bash
cd infra/aws/bootstrap

cat > /tmp/bootstrap-backend.hcl <<'HCL'
bucket         = "cloudradar-tfstate-<account-id>"
key            = "bootstrap/terraform.tfstate"
region         = "us-east-1"
dynamodb_table = "cloudradar-tf-lock"
encrypt        = true
HCL

terraform init -backend-config=/tmp/bootstrap-backend.hcl -migrate-state
```

## Related issues
- #33
- #6
- #341
