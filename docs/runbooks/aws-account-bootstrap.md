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

### 6) Backend bootstrap policy (S3 + DynamoDB)
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

### 6.1) Infra MVP policy (VPC + EC2 + EBS + routes + backend access)
This policy enables MVP infrastructure provisioning (VPC + EC2 + EBS + SG + routes)
without granting admin rights.

Save as `cloudradar-infra-baseline.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "VpcEc2Baseline",
      "Effect": "Allow",
      "Action": [
        "ec2:CreateVpc",
        "ec2:DeleteVpc",
        "ec2:ModifyVpcAttribute",
        "ec2:CreateSubnet",
        "ec2:DeleteSubnet",
        "ec2:CreateInternetGateway",
        "ec2:DeleteInternetGateway",
        "ec2:AttachInternetGateway",
        "ec2:DetachInternetGateway",
        "ec2:CreateRouteTable",
        "ec2:DeleteRouteTable",
        "ec2:CreateRoute",
        "ec2:DeleteRoute",
        "ec2:AssociateRouteTable",
        "ec2:DisassociateRouteTable",
        "ec2:CreateVpcEndpoint",
        "ec2:DeleteVpcEndpoints",
        "ec2:ModifyVpcEndpoint",
        "ec2:DescribeVpcEndpoints",
        "ec2:DescribeVpcEndpointServices",
        "ec2:CreateSecurityGroup",
        "ec2:DeleteSecurityGroup",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:AuthorizeSecurityGroupEgress",
        "ec2:RevokeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupEgress",
        "ec2:RunInstances",
        "ec2:TerminateInstances",
        "ec2:CreateVolume",
        "ec2:DeleteVolume",
        "ec2:AttachVolume",
        "ec2:DetachVolume",
        "ec2:AllocateAddress",
        "ec2:ReleaseAddress",
        "ec2:AssociateAddress",
        "ec2:DisassociateAddress",
        "ec2:CreateTags",
        "ec2:DeleteTags",
        "ec2:ModifyInstanceAttribute",
        "ec2:Describe*"
      ],
      "Resource": "*"
    },
    {
      "Sid": "Ec2LaunchTemplates",
      "Effect": "Allow",
      "Action": [
        "ec2:CreateLaunchTemplate",
        "ec2:CreateLaunchTemplateVersion",
        "ec2:DeleteLaunchTemplate",
        "ec2:DescribeLaunchTemplates",
        "ec2:DescribeLaunchTemplateVersions"
      ],
      "Resource": "*"
    },
    {
      "Sid": "AutoScalingForK3s",
      "Effect": "Allow",
      "Action": [
        "autoscaling:CreateAutoScalingGroup",
        "autoscaling:UpdateAutoScalingGroup",
        "autoscaling:DeleteAutoScalingGroup",
        "autoscaling:CreateOrUpdateTags",
        "autoscaling:Describe*"
      ],
      "Resource": "*"
    },
    {
      "Sid": "TerraformStateS3",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetBucketLocation"
      ],
      "Resource": "arn:aws:s3:::cloudradar-tfstate-<account-id>"
    },
    {
      "Sid": "TerraformStateObjects",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::cloudradar-tfstate-<account-id>/*"
    },
    {
      "Sid": "TerraformStateLock",
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:DeleteItem",
        "dynamodb:DescribeTable",
        "dynamodb:UpdateItem"
      ],
      "Resource": "arn:aws:dynamodb:us-east-1:<account-id>:table/cloudradar-tf-lock"
    },
    {
      "Sid": "IamForK3sRoles",
      "Effect": "Allow",
      "Action": [
        "iam:CreateRole",
        "iam:DeleteRole",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:UpdateAssumeRolePolicy",
        "iam:PutRolePolicy",
        "iam:DeleteRolePolicy",
        "iam:AttachRolePolicy",
        "iam:DetachRolePolicy",
        "iam:TagRole",
        "iam:UntagRole",
        "iam:CreateInstanceProfile",
        "iam:DeleteInstanceProfile",
        "iam:GetInstanceProfile",
        "iam:AddRoleToInstanceProfile",
        "iam:RemoveRoleFromInstanceProfile",
        "iam:ListInstanceProfilesForRole",
        "iam:ListRolePolicies",
        "iam:ListAttachedRolePolicies",
        "iam:PassRole"
      ],
      "Resource": [
        "arn:aws:iam::<account-id>:role/cloudradar-*",
        "arn:aws:iam::<account-id>:instance-profile/cloudradar-*"
      ]
    },
    {
      "Sid": "SsmPutEdgeBasicAuth",
      "Effect": "Allow",
      "Action": [
        "ssm:PutParameter"
      ],
      "Resource": "arn:aws:ssm:us-east-1:<account-id>:parameter/cloudradar/edge/basic-auth"
    },
    {
      "Sid": "SsmValidation",
      "Effect": "Allow",
      "Action": [
        "ssm:DescribeInstanceInformation",
        "ssm:SendCommand",
        "ssm:GetCommandInvocation",
        "ssm:ListCommandInvocations"
      ],
      "Resource": "*"
    },
    {
      "Sid": "AllowAutoScalingServiceLinkedRole",
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "arn:aws:iam::<account-id>:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"
    }
  ]
}
```

Attach it to the Terraform role:

```bash
aws iam put-role-policy \
  --role-name CloudRadarTerraformRole \
  --policy-name CloudRadarInfraBaseline \
  --policy-document file://cloudradar-infra-baseline.json
```

### 6.2) Policy summary table

| Policy | Purpose | Services | Used by |
| --- | --- | --- | --- |
| Backend bootstrap policy | Create state/lock for Terraform | S3, DynamoDB | CloudRadarTerraformRole |
| Infra MVP policy | Provision MVP infra + backend access | EC2/VPC, EBS, SGs, Routes, EIP, Launch Templates, Auto Scaling, IAM, S3, DynamoDB, SSM | CloudRadarTerraformRole |

### 6.4) Edge prerequisites (Basic Auth)

The edge Nginx instance reads the Basic Auth password from SSM Parameter Store at boot.
Create the parameter before applying the edge module.
Ensure the Terraform role can call `ssm:PutParameter` for `/cloudradar/edge/basic-auth`.
Set `edge_root_volume_size` to at least 30 GB (40 GB recommended) for AL2023.

Example:

```bash
aws ssm put-parameter \
  --name "/cloudradar/edge/basic-auth" \
  --type "SecureString" \
  --value "change-me" \
  --overwrite
```

### 6.5) Session Manager plugin (local)

SSM port forwarding requires the Session Manager plugin installed locally.

Required IAM permissions for the role/user running the tunnel:

```json
{
  "Sid": "SsmStartSession",
  "Effect": "Allow",
  "Action": [
    "ssm:StartSession",
    "ssm:TerminateSession",
    "ssm:DescribeSessions",
    "ssm:GetConnectionStatus",
    "ssm:DescribeInstanceInformation"
  ],
  "Resource": [
    "arn:aws:ec2:us-east-1:<account-id>:instance/*",
    "arn:aws:ssm:us-east-1:<account-id>:document/SSM-SessionManagerRunShell",
    "arn:aws:ssm:*::document/AWS-StartPortForwardingSessionToRemoteHost",
    "arn:aws:ssm:*::document/AWS-StartPortForwardingSession"
  ]
}
```

Note: if you assume a role with `aws-refresh-token.sh`, unset `AWS_PROFILE` so the CLI uses the exported STS credentials.

Install (Ubuntu):

```bash
curl -sSLo /tmp/session-manager-plugin.deb \
  https://s3.amazonaws.com/session-manager-downloads/plugin/latest/ubuntu_64bit/session-manager-plugin.deb

sudo dpkg -i /tmp/session-manager-plugin.deb
session-manager-plugin --version
```

Example tunnel (background, close with PID):

```bash
aws ssm start-session \
  --target <instance-id> \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["127.0.0.1"],"portNumber":["6443"],"localPortNumber":["6443"]}' \
  > /tmp/ssm-k3s-tunnel.log 2>&1 &

echo $! > /tmp/ssm-k3s-tunnel.pid
```

Fetch kubeconfig from k3s (via SSM) and use the tunnel:

```bash
command_id="$(aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters commands='["sudo cat /etc/rancher/k3s/k3s.yaml"]' \
  --query "Command.CommandId" \
  --output text)"

aws ssm get-command-invocation \
  --command-id "${command_id}" \
  --instance-id <instance-id> \
  --query "StandardOutputContent" \
  --output text > /tmp/k3s-aws.yaml

sed -i 's#https://127.0.0.1:6443#https://127.0.0.1:16443#' /tmp/k3s-aws.yaml
export KUBECONFIG=/tmp/k3s-aws.yaml
kubectl get nodes
```

Close the tunnel:

```bash
kill "$(cat /tmp/ssm-k3s-tunnel.pid)"
```

Reset kubeconfig when done:

```bash
unset KUBECONFIG
```

### 6.3) MVP tickets mapped to permissions

| Ticket | Area | Permissions needed |
| --- | --- | --- |
| #1 | VPC baseline | VPC, Subnets, IGW, Routes, SG, Tags, Describe |
| #7 | k3s EC2 nodes | EC2, EBS, SG, Tags, Describe |
| #8 | Edge Nginx EC2 | EC2, EIP, SG, Tags, Describe |
| #11 | Backups to S3 | S3 (bucket + objects) |
| Backend (state/lock) | Terraform | S3 (state), DynamoDB (lock) |

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
- Local IAM user (CLI): `CloudRadarTerraformUser` (assumes the Terraform role)
- CI role: `CloudRadarTerraformRole`
- OIDC provider tag/name: `github-actions-oidc`

## Auth paths (GitHub Actions vs AWS CLI)
- **GitHub Actions** uses OIDC to assume `CloudRadarTerraformRole` (no IAM user or static keys).
- **AWS CLI (local)** uses a profile for `CloudRadarTerraformUser`, then assumes `CloudRadarTerraformRole`.
  The user needs only `sts:AssumeRole` + MFA.

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
