#!/usr/bin/env bash
set -euo pipefail

# Bootstrap AWS account settings for CloudRadar (steps 3-7).
# Requires AWS CLI v2 and credentials with permissions to manage IAM and Budgets.

AWS_REGION="${AWS_REGION:-us-east-1}"
ROLE_NAME="${ROLE_NAME:-CloudRadarTerraformRole}"
OIDC_PROVIDER_URL="${OIDC_PROVIDER_URL:-token.actions.githubusercontent.com}"
OIDC_PROVIDER_TAG="${OIDC_PROVIDER_TAG:-github-actions-oidc}"
REPO_SLUG="${REPO_SLUG:-ClementV78/CloudRadar}"
BUDGET_AMOUNT="${BUDGET_AMOUNT:-10}"
BUDGET_NAME="${BUDGET_NAME:-cloudradar-monthly-budget}"
ALERT_EMAIL="${ALERT_EMAIL:-alerts@example.com}"

STATE_BUCKET_PREFIX="${STATE_BUCKET_PREFIX:-cloudradar-tfstate-}"
LOCK_TABLE_NAME="${LOCK_TABLE_NAME:-cloudradar-tf-lock}"

# GitHub OIDC root CA thumbprint used by IAM to trust token.actions.githubusercontent.com.
GITHUB_OIDC_THUMBPRINT="${GITHUB_OIDC_THUMBPRINT:-6938fd4d98bab03faadb97b34396831e3780aea1}"

# Resolve the current AWS account for outputs and ARNs.
account_id="$(aws sts get-caller-identity --query Account --output text)"

echo "Account ID: ${account_id}"
echo "Region: ${AWS_REGION}"

find_oidc_provider_arn() {
  local arn
  for arn in $(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text); do
    local url
    url="$(aws iam get-open-id-connect-provider --open-id-connect-provider-arn "${arn}" --query Url --output text)"
    if [[ "${url}" == "${OIDC_PROVIDER_URL}" ]]; then
      echo "${arn}"
      return 0
    fi
  done
  return 1
}

oidc_provider_arn="$(find_oidc_provider_arn || true)"
if [[ -z "${oidc_provider_arn}" ]]; then
  echo "Creating OIDC provider..."
  oidc_provider_arn="$(aws iam create-open-id-connect-provider \
    --url "https://${OIDC_PROVIDER_URL}" \
    --client-id-list "sts.amazonaws.com" \
    --thumbprint-list "${GITHUB_OIDC_THUMBPRINT}" \
    --tags "Key=Name,Value=${OIDC_PROVIDER_TAG}" \
    --query OpenIDConnectProviderArn \
    --output text)"
else
  echo "OIDC provider exists: ${oidc_provider_arn}"
fi

# Trust policy limited to this repo and OIDC audience.
trust_policy="$(mktemp)"
cat > "${trust_policy}" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "${oidc_provider_arn}"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "${OIDC_PROVIDER_URL}:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "${OIDC_PROVIDER_URL}:sub": "repo:${REPO_SLUG}:*"
        }
      }
    }
  ]
}
EOF

if aws iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
  echo "Updating trust policy for role ${ROLE_NAME}..."
  aws iam update-assume-role-policy --role-name "${ROLE_NAME}" --policy-document "file://${trust_policy}"
else
  echo "Creating role ${ROLE_NAME}..."
  aws iam create-role --role-name "${ROLE_NAME}" --assume-role-policy-document "file://${trust_policy}"
fi

# Minimal inline policy for backend bootstrap (S3 + DynamoDB).
inline_policy="$(mktemp)"
cat > "${inline_policy}" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:CreateBucket"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutBucketEncryption",
        "s3:PutBucketVersioning",
        "s3:PutPublicAccessBlock",
        "s3:PutBucketTagging",
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::${STATE_BUCKET_PREFIX}*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DescribeTable",
        "dynamodb:TagResource"
      ],
      "Resource": "arn:aws:dynamodb:${AWS_REGION}:${account_id}:table/${LOCK_TABLE_NAME}"
    }
  ]
}
EOF

echo "Attaching inline policy to role..."
aws iam put-role-policy \
  --role-name "${ROLE_NAME}" \
  --policy-name "CloudRadarTerraformBootstrap" \
  --policy-document "file://${inline_policy}"

# Budget API can return null/None; handle gracefully to avoid jq length() errors.
budget_count="0"
if budget_count_raw="$(aws budgets describe-budgets --account-id "${account_id}" --query "Budgets[?BudgetName=='${BUDGET_NAME}'] | length(@)" --output text 2>/dev/null)"; then
  if [[ "${budget_count_raw}" != "None" && -n "${budget_count_raw}" ]]; then
    budget_count="${budget_count_raw}"
  fi
else
  echo "Budget check failed; attempting to create ${BUDGET_NAME}..."
fi

if [[ "${budget_count}" == "0" ]]; then
  echo "Creating budget ${BUDGET_NAME} (${BUDGET_AMOUNT} USD)..."
  budget_file="$(mktemp)"
  cat > "${budget_file}" <<EOF
{
  "BudgetName": "${BUDGET_NAME}",
  "BudgetLimit": {
    "Amount": "${BUDGET_AMOUNT}",
    "Unit": "USD"
  },
  "BudgetType": "COST",
  "TimeUnit": "MONTHLY"
}
EOF

  notification_file="$(mktemp)"
  cat > "${notification_file}" <<EOF
[
  {
    "Notification": {
      "NotificationType": "ACTUAL",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 100,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [
      {
        "SubscriptionType": "EMAIL",
        "Address": "${ALERT_EMAIL}"
      }
    ]
  }
]
EOF

  aws budgets create-budget \
    --account-id "${account_id}" \
    --budget "file://${budget_file}" \
    --notifications-with-subscribers "file://${notification_file}"
else
  echo "Budget ${BUDGET_NAME} already exists."
fi

echo ""
echo "Outputs:"
echo "AWS_ACCOUNT_ID=${account_id}"
echo "OIDC_PROVIDER_ARN=${oidc_provider_arn}"
echo "TERRAFORM_CI_ROLE_ARN=arn:aws:iam::${account_id}:role/${ROLE_NAME}"
