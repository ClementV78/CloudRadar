# DNS Delegation (Registrar/DNS Provider → Route53) for CloudRadar

Goal: serve Grafana/Prometheus under stable FQDNs without `/etc/hosts`, and keep DNS updated after destroy/rebuild.

## Overview

- Primary domain stays at your registrar / DNS provider (e.g., Cloudflare).
- Subdomain `cloudradar.example.com` is delegated to Route53.
- Terraform manages the Route53 A records (no out-of-band workflow mutation).
- Grafana reads its `domain` and `root_url` from SSM via External Secrets (SSM params are written by Terraform).

## Costs (Route53)

- Hosted Zone: ~$0.50/month
- Queries: negligible for MVP

## Prerequisites

- Access to the DNS zone for your primary domain at your registrar.
- AWS credentials with Route53 + SSM permissions (already used by `ci-infra`).

## Step 1: Create the Route53 hosted zone

The zone is created by Terraform **only if** `dns_zone_name` is set.

Implementation detail: the hosted zone is managed in `infra/aws/bootstrap` (same Terraform root as the backend resources). This keeps the hosted zone out of the environment state, so `ci-infra-destroy` can tear down the env without touching the DNS delegation.

If you created the hosted zone with an older version (where it lived in `infra/aws/live/dev` state), the first `ci-infra` apply can detach it from the env state (migration) so future destroys won't delete it.

Recommended: set the value via the `DNS_ZONE_NAME` GitHub Actions variable so it is not committed.

To create the hosted zone, run the `bootstrap-terraform-backend` workflow and provide `dns_zone_name` (or set the `DNS_ZONE_NAME` repo variable so the workflow picks it up by default).

Example value (keep real values out of the repo):

```
cloudradar.example.com
```

Do not commit real domain values. The `ci-infra` workflow reads `DNS_ZONE_NAME` from GitHub Actions variables and will fail early on dev applies if it is missing.

After apply, the zone name servers are available in Terraform outputs:

```bash
terraform -chdir=infra/aws/live/dev output -json dns_zone_name_servers
```

## Step 2: Delegate the subdomain in your DNS provider

In your registrar DNS for `example.com`, add **NS** records:

- **Name**: `cloudradar`
- **Type**: `NS`
- **Values**: the 4 name servers from Route53

DNS propagation can take a few minutes.

## Step 3: Run `ci-infra`

`ci-infra` (Terraform apply, requires `auto_approve=true`) will:
- create/update Route53 A records for:
  - `cloudradar.example.com`
  - `grafana.cloudradar.example.com`
  - `prometheus.cloudradar.example.com`
  - `app.cloudradar.example.com`
- write SSM parameters (consumed by External Secrets):
  - `/cloudradar/grafana-domain`
  - `/cloudradar/grafana-root-url`

## Step 4: Validate

```bash
dig +short grafana.cloudradar.example.com
dig +short prometheus.cloudradar.example.com
```

Grafana:
```
https://grafana.cloudradar.example.com/grafana/
```

Prometheus:
```
https://prometheus.cloudradar.example.com/prometheus/
```

## Rebuild behavior (destroy → apply)

After a rebuild, run `ci-infra` again.

- The hosted zone stays stable (delegation NS does not change) as long as you don't destroy the hosted zone itself.
- The A records will be updated to the new edge IP automatically by Terraform.

## Troubleshooting

- DNS not resolving: check the NS delegation in your DNS provider and wait for propagation.
- Grafana login errors: confirm SSM parameters are present and Grafana pod restarted after secret refresh.
