# Offline Fallback Landing Page (Route53 Failover)

## Purpose
Serve a branded CloudRadar offline landing page with demo-contact form when live infrastructure is down (or intentionally destroyed), while keeping the online stack unchanged.

## Architecture summary
- Primary path (online): `cloudradar.<domain>` -> edge Nginx (existing, Let's Encrypt cert from SSM).
- Failover path (offline): Route53 failover switches to CloudFront offline distribution backed by dedicated failover S3.
- Both failover records use the same public hostname (`cloudradar.<domain>`); Route53 returns PRIMARY or SECONDARY target depending on primary health-check status.
- Contact form: `/api/contact-demo` -> CloudFront behavior -> API Gateway HTTP API -> Lambda -> SES.
- Anti-spam baseline (no WAF): API throttling + honeypot + backend validation + DynamoDB IP/window rate limit.

## Offline fallback diagram
```mermaid
flowchart LR
  USER["User browser"]
  R53["Route53 failover record<br/>cloudradar.domain.tld"]
  HC["Primary health check<br/>https://live.domain.tld/statusz"]
  ONLINE["PRIMARY target<br/>live.domain.tld -> edge Nginx"]
  OFFLINE["SECONDARY target<br/>CloudFront offline distribution"]
  S3["S3 offline static assets"]
  CONTACT["POST /api/contact-demo"]
  APIGW["API Gateway HTTP API"]
  LAMBDA["Lambda contact handler"]
  DDB["DynamoDB rate limit"]
  SES["SES email send"]

  HC -. evaluates .-> R53
  USER --> R53
  R53 -->|PRIMARY healthy| ONLINE
  R53 -->|PRIMARY unhealthy| OFFLINE

  OFFLINE --> S3
  OFFLINE --> CONTACT
  CONTACT --> APIGW --> LAMBDA
  LAMBDA --> DDB
  LAMBDA --> SES
```

## Prerequisites
- `DNS_ZONE_NAME` configured in bootstrap workflow vars.
- Hosted zone managed by `infra/aws/bootstrap`.
- Failover stack managed by `infra/aws/failover` (`ci-failover` workflow).
- SES available in the selected region.

## Required GitHub Action variables
Set in repository variables before running `ci-failover`:
- `OFFLINE_SITE_ENABLED=true`
- `OFFLINE_CONTACT_SENDER_LOCAL_PART=<sender-local-part>` (optional; default: `noreply`)
- `OFFLINE_CONTACT_RECIPIENT_EMAIL=<your-mailbox>`

Optional tuning:
- `OFFLINE_SITE_BUCKET_NAME`
- `OFFLINE_SUBDOMAIN_LABEL` (default: `offline`)
- `OFFLINE_PRIMARY_DOMAIN_LABEL` (default: `live`)
- `OFFLINE_LOGS_RETENTION_DAYS` (default: `7`)
- `OFFLINE_RATE_LIMIT_WINDOW_SECONDS` (default: `900`)
- `OFFLINE_RATE_LIMIT_MAX_HITS` (default: `3`)
- `OFFLINE_API_THROTTLE_RATE_LIMIT` (default: `5`)
- `OFFLINE_API_THROTTLE_BURST_LIMIT` (default: `10`)
- `OFFLINE_PRIMARY_HEALTH_PATH` (default: `/statusz`)
- `OFFLINE_PRIMARY_HEALTH_PORT` (default: `443`)

## Apply order
Recommended sequence for this project:
1. Run `bootstrap-terraform-backend` (backend + DNS zone + optional TLS issuance).
2. Run `ci-infra` for live env (creates/updates `live.<zone>` record used by failover health checks).
3. Run `ci-failover` (`action=apply`, `auto_approve=true`).

If online infra is intentionally destroyed and `live.<zone>` is absent:
- run `ci-failover` with `offline_mode=true` to keep failover stack deployable/manageable.

In CI, use workflows:
1. `bootstrap-terraform-backend` for backend + optional DNS/TLS bootstrap.
2. `ci-infra` for live env.
3. `ci-failover` for offline fallback stack + Route53 failover records.

## Verification checklist
1. Validate offline DNS records:
```bash
aws route53 list-resource-record-sets --hosted-zone-id <ZONE_ID> \
  --query "ResourceRecordSets[?Name=='cloudradar.<domain>.' || Name=='offline.cloudradar.<domain>.' || Name=='live.cloudradar.<domain>.']"
```
2. Verify Route53 health check is healthy (primary):
```bash
aws route53 list-health-checks --query "HealthChecks[?contains(FullyQualifiedDomainName, 'live.')].Id"
```
3. Verify CloudFront distribution deployed and aliases include root + offline domains.
4. Verify SES domain identity records are present in Route53:
   - `_amazonses.<dns_zone_name>` TXT
   - `*._domainkey.<dns_zone_name>` CNAME (DKIM)
5. Open `https://offline.<zone>` and submit a test contact request.
6. Confirm SES email reception.

### SES sandbox note
If SES is still in sandbox mode (`ProductionAccessEnabled=false`), the recipient identity must be verified.
The Terraform Lambda IAM policy authorizes:
- domain identity (`identity/<dns_zone_name>`)
- recipient identity (`identity/<offline_contact_recipient_email>`)
to prevent `AccessDenied` at `ses:SendEmail`.

## Spam-protection checks
- Submit 1 valid request -> expected `200`.
- Submit a request with honeypot field set -> expected `400`.
- Submit > configured `OFFLINE_RATE_LIMIT_MAX_HITS` in one window -> expected `429`.

## Rollback
- Set `OFFLINE_SITE_ENABLED=false` and run `ci-failover` with `action=apply`.
- Or run `ci-failover` with `action=destroy` and `confirm_destroy=DESTROY`.

## Notes
- No WAF in v1.1 by FinOps choice.
- CloudWatch retention should remain in the existing 3-7 day policy range.
