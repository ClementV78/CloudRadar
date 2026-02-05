# DNS Delegation (OVH → Route53) for CloudRadar

Goal: serve Grafana/Prometheus under stable FQDNs without `/etc/hosts`, and keep DNS updated after destroy/rebuild.

## Overview

- Primary domain stays at your registrar (e.g., OVH).
- Subdomain `cloudradar.example.com` is delegated to Route53.
- `ci-infra` updates the Route53 A records after each apply.
- Grafana reads its `domain` and `root_url` from SSM via External Secrets.

## Costs (Route53)

- Hosted Zone: ~$0.50/month
- Queries: negligible for MVP

## Prerequisites

- Access to the DNS zone for your primary domain at your registrar.
- AWS credentials with Route53 + SSM permissions (already used by `ci-infra`).

## Step 1: Create the Route53 hosted zone

The zone is created by Terraform **only if** `dns_zone_name` is set.

Recommended: set the value at `ci-infra` dispatch time so it is not committed.

Example dispatch input (keep real values out of the repo):

```
dns_zone_name = cloudradar.example.com
```

Do not commit real domain values. Use the workflow input or the `DNS_ZONE_NAME` GitHub Actions variable.

After apply, the zone name servers are available in Terraform outputs:

```bash
terraform -chdir=infra/aws/live/dev output -json dns_zone_name_servers
```

## Step 2: Delegate the subdomain in OVH

In your registrar DNS for `example.com`, add **NS** records:

- **Name**: `cloudradar`
- **Type**: `NS`
- **Values**: the 4 name servers from Route53

DNS propagation can take a few minutes.

## Step 3: Run `ci-infra`

`ci-infra` will:
- upsert Route53 A records for:
  - `cloudradar.example.com`
  - `grafana.cloudradar.example.com`
  - `prometheus.cloudradar.example.com`
  - `app.cloudradar.example.com`
- write SSM parameters:
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
The DNS records will be updated to the new edge IP automatically.

## Troubleshooting

- DNS not resolving: check the OVH NS delegation and wait for propagation.
- Grafana login errors: confirm SSM parameters are present and Grafana pod restarted after secret refresh.
