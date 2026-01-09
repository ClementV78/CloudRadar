# AWS Account Bootstrap (CloudRadar)

## Purpose
Establish the AWS account baseline and GitHub Actions OIDC access required before Terraform IaC runs.

## Scope
- Account creation and root security
- Billing and cost alerts
- OIDC provider and CI role for GitHub Actions
- Documented outputs for downstream workflows

## Prerequisites
- AWS account email and phone number
- MFA device available for root user
- Access to the GitHub repo: `ClementV78/CloudRadar`
- Target AWS region: `us-east-1`

## Steps

### 1) Create the AWS account
- Use a dedicated email address (e.g. `cloudradar@...`).
- Enable MFA on the root user immediately.
- **Do not create root access keys.**

### 2) Secure root access
- Confirm MFA is enabled.
- Store recovery codes in a secure location.
- Verify account contact details and alternate contacts.

### 3) Billing and cost visibility
- Enable AWS Billing console access for IAM users (console toggle).
- Create a billing alarm for monthly spend (e.g. $10) using AWS Budgets.
- Enable Cost Explorer (console toggle if not already enabled).

### 4) Create GitHub OIDC provider
- IAM → Identity providers → Add provider.
- Provider URL: `https://token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`
- Name: `github-actions-oidc`

### 5) Create CI role for Terraform (least-privilege)
- IAM → Roles → Create role → Web identity.
- Provider: `token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`
- Trust policy restricts to repo `ClementV78/CloudRadar`.

Example trust policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:ClementV78/CloudRadar:*"
        }
      }
    }
  ]
}
```

### 6) Attach minimal policy for bootstrap
Start with permissions required to create:
- S3 bucket (state)
- DynamoDB table (lock)
- IAM role inspection (optional)

Record the policy ARN used for CI.

Minimal policy example:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:CreateBucket",
        "s3:PutBucketEncryption",
        "s3:PutBucketVersioning",
        "s3:PutPublicAccessBlock",
        "s3:PutBucketTagging",
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::cloudradar-tfstate-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DescribeTable",
        "dynamodb:TagResource"
      ],
      "Resource": "arn:aws:dynamodb:*:*:table/cloudradar-tf-lock"
    }
  ]
}
```

### 7) Record outputs for CI
- AWS Account ID
- OIDC Provider ARN
- CI Role ARN

Store these values in a secure place and in GitHub Actions variables if needed.

## Outputs
- `AWS_ACCOUNT_ID`
- `OIDC_PROVIDER_ARN`
- `TERRAFORM_CI_ROLE_ARN`

## IAM Artifacts (document your final values)
- Bootstrap IAM user: `CloudRadarBootstrapUser`
- MFA device name: `<MFA_DEVICE_NAME>` (e.g., `bitwarden_<name>`)
- Bootstrap role: `CloudRadarBootstrapRole`
- CI role: `CloudRadarTerraformRole`
- OIDC provider tag/name: `github-actions-oidc`

### Bootstrap role permissions (simplified)
If you had to broaden permissions during bootstrap, keep them scoped to the
target bucket and table (not account-wide):

```json
{
  "Effect": "Allow",
  "Action": "s3:*",
  "Resource": [
    "arn:aws:s3:::cloudradar-tfstate-<account-id>",
    "arn:aws:s3:::cloudradar-tfstate-<account-id>/*"
  ]
},
{
  "Effect": "Allow",
  "Action": "dynamodb:*",
  "Resource": "arn:aws:dynamodb:us-east-1:<account-id>:table/cloudradar-tf-lock"
}
```

## Verification
- Confirm OIDC provider exists in IAM.
- Confirm CI role trust policy matches repo.
- Confirm MFA enabled and no root keys.
- Confirm AWS Budget is active and email notifications are configured.
- Validate the CI role can be assumed (manual test in AWS console is sufficient).

## Definition of Done
- Root account secured with MFA and no access keys.
- Billing alarm created and Cost Explorer enabled.
- OIDC provider and CI role created with repo-restricted trust policy.
- Outputs captured for CI usage.

## Notes
- The Terraform backend bootstrap workflow (#33) depends on this setup.
- Keep real emails and account identifiers out of committed files; use placeholders in the repo.

## Related issues
- #32

## Automation (AWS CLI)
Steps 3–7 can be automated with a lightweight script:

```bash
AWS_REGION=us-east-1 \
ROLE_NAME=CloudRadarTerraformRole \
OIDC_PROVIDER_TAG=github-actions-oidc \
BUDGET_AMOUNT=10 \
ALERT_EMAIL=alerts@example.com \
scripts/bootstrap-aws.sh
```

Notes:
- Steps 1–2 remain manual (account creation + root MFA).
- Billing console access and Cost Explorer are console toggles; the script does not modify them.

### What the script does (manual mapping)
- **Step 3 (Budgets)**: creates a monthly AWS Budget and email notification if it does not exist.
- **Step 4 (OIDC provider)**: creates the IAM OIDC provider for `token.actions.githubusercontent.com` if missing.
- **Step 5 (CI role)**: creates or updates the IAM role trust policy for GitHub Actions OIDC.
- **Step 6 (policy)**: attaches a minimal inline policy for S3 state and DynamoDB lock creation.
- **Step 7 (outputs)**: prints the account ID, OIDC provider ARN, and CI role ARN.

### Script inputs
- `AWS_REGION` (default `us-east-1`)
- `ROLE_NAME` (default `CloudRadarTerraformRole`)
- `OIDC_PROVIDER_TAG` (default `github-actions-oidc`)
- `BUDGET_AMOUNT` (default `10`)
- `ALERT_EMAIL` (default `alerts@example.com`)
- `REPO_SLUG` (default `ClementV78/CloudRadar`)

### Assume the bootstrap role (no temp file)
Use MFA and export credentials directly in your shell:

```bash
read -r AK SK ST <<< "$(aws sts assume-role \
  --profile cloudradar-bootstrap \
  --role-arn arn:aws:iam::<ACCOUNT_ID>:role/CloudRadarBootstrapRole \
  --role-session-name cloudradar-bootstrap \
  --serial-number arn:aws:iam::<ACCOUNT_ID>:mfa/<MFA_DEVICE_NAME> \
  --token-code <CODE_MFA> \
  --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
  --output text)"

export AWS_ACCESS_KEY_ID="$AK"
export AWS_SECRET_ACCESS_KEY="$SK"
export AWS_SESSION_TOKEN="$ST"
```
