# Runbook: ci-failover workflow

This runbook explains the `ci-failover` GitHub Actions workflow used to manage the persistent offline fallback stack.

## Purpose
Manage offline fallback infrastructure in a dedicated Terraform state (`cloudradar/failover/terraform.tfstate`) so it is not impacted by `ci-infra-destroy`.
The workflow also keeps user-visible switch latency low by invalidating offline CloudFront HTML entrypoints (`/` and `/index.html`) after apply.

## Terraform root
- `infra/aws/failover`

## Workflow file
- `.github/workflows/ci-failover.yml`

## Prerequisites
Set these GitHub Actions variables:
- `AWS_FAILOVER_TERRAFORM_ROLE_ARN`
- `AWS_REGION`
- `TF_STATE_BUCKET`
- `TF_LOCK_TABLE_NAME`
- `DNS_ZONE_NAME`
- `OFFLINE_CONTACT_RECIPIENT_EMAIL` (required when `OFFLINE_SITE_ENABLED=true`)

Runtime prerequisites:
- `live.<dns_zone_name>` record must already exist (created by `ci-infra`), because Route53 PRIMARY failover aliases that record.
- The role behind `AWS_FAILOVER_TERRAFORM_ROLE_ARN` must include CloudFront/Route53/ACM/SES/API Gateway/Lambda/IAM/Logs permissions required by `infra/aws/failover`.
- For intentional offline-only operation (live infra destroyed), enable `offline_mode=true` at workflow dispatch.

Optional tuning:
- `OFFLINE_SITE_ENABLED` (default: `true`)
- `OFFLINE_SITE_BUCKET_NAME`
- `OFFLINE_SUBDOMAIN_LABEL` (default: `offline`)
- `OFFLINE_PRIMARY_DOMAIN_LABEL` (default: `live`)
- `OFFLINE_CONTACT_SENDER_LOCAL_PART` (default: `noreply`)
- `OFFLINE_LOGS_RETENTION_DAYS` (default: `7`)
- `OFFLINE_RATE_LIMIT_WINDOW_SECONDS` (default: `900`)
- `OFFLINE_RATE_LIMIT_MAX_HITS` (default: `3`)
- `OFFLINE_API_THROTTLE_RATE_LIMIT` (default: `5`)
- `OFFLINE_API_THROTTLE_BURST_LIMIT` (default: `10`)
- `OFFLINE_PRIMARY_HEALTH_PATH` (default: `/statusz`)
- `OFFLINE_PRIMARY_HEALTH_PORT` (default: `443`)
- `OFFLINE_ROUTE53_FAILOVER_ENABLED` (default: `true`)

## Actions
`workflow_dispatch` inputs:
- `action`: `plan` | `apply` | `destroy`
- `auto_approve`: required `true` for `apply`
- `import_existing`: best-effort migration import before plan/apply
- `offline_mode`: allow run when `live.<dns_zone_name>` A record is missing
- `confirm_destroy`: must be `DESTROY` for `destroy`

## Offline-only usage
When online infrastructure is intentionally destroyed:
1. Run `ci-failover` with:
   - `action=apply`
   - `auto_approve=true`
   - `offline_mode=true`
2. The workflow keeps Route53 failover resources manageable even if `live.<dns_zone_name>` does not exist.

## Recommended order
1. `bootstrap-terraform-backend`
2. `ci-infra` (dev/prod)
3. `ci-failover` (`action=apply`, `auto_approve=true`)

## Safety notes
- `ci-failover` uses a dedicated remote state key and lock table.
- `ci-infra-destroy` does not remove failover resources.
- Route53 failover remains active when live infra is down.
- Cache guardrails:
  - `index.html` is configured with `Cache-Control: no-store, max-age=0, must-revalidate`.
  - `/api/contact-demo` uses a disabled CloudFront cache policy and response `Cache-Control: no-store`.
