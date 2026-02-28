# ADR-0020: Edge TLS Certificate Lifecycle (MVP)

Date: 2026-02-28
Status: Accepted

## Context

CloudRadar exposes public HTTPS traffic through an Edge Nginx EC2 instance.
The MVP must provide a valid certificate for `cloudradar.iotx.fr` while keeping:
- low operational cost (no mandatory ALB/managed edge stack),
- frequent destroy/redeploy compatibility,
- no private key in Git or Terraform state,
- port 80 closed in nominal mode.

Issue #531 defines the bootstrap/deploy scope.
Issue #532 defines renewal monitoring and controlled renewal execution.

## Decision

### 1) Certificate issuance and deployment baseline (implemented in #531)

1. Use **Let's Encrypt DNS-01** challenge with Route53 for certificate issuance.
2. Trigger issuance from the manual bootstrap workflow (`bootstrap-terraform-backend`) using `issue_tls=true` and `tls_domain`.
3. Store certificate artifacts in **AWS SSM Parameter Store**:
   - `/cloudradar/edge/tls/fullchain_pem` (`SecureString`)
   - `/cloudradar/edge/tls/privkey_pem` (`SecureString`)
   - `/cloudradar/edge/tls/metadata` (`String`)
4. Keep private key material out of Git and Terraform state; write values only at runtime from workflow execution.
5. Enforce **strict edge boot behavior**:
   - edge loads certificate/key from SSM,
   - edge startup fails if artifacts are missing, expired, or mismatched,
   - no self-signed fallback in nominal mode.

### 2) Renewal and alerting strategy (planned in #532)

1. Monitor certificate validity as `days_to_expiry` for the public endpoint.
2. Define alert thresholds:
   - warning: <= 30 days,
   - critical: <= 14 days.
3. Provide a controlled renewal entrypoint (manual + alert-trigger path) that:
   - re-issues certificate via DNS-01,
   - updates SSM artifacts,
   - redeploys/reloads edge TLS safely,
   - runs post-renew checks (CN/SAN, trust, expiry).
4. Add idempotency guard to avoid unnecessary renewals when remaining validity is above threshold.

### 3) Security and operational guardrails

- No sensitive local endpoint details in repository docs/issues.
- CI/workflow logs are the audit trail for issue/store/deploy/verify phases.
- Runtime access uses least-privilege IAM (`ssm:GetParameter`, `kms:Decrypt` where required).

## Consequences

### Positive

- Public HTTPS certificate management is compatible with frequent infra redeploys.
- Port 80 is not required for ACME validation.
- Edge remains low-cost and simple (Nginx on EC2) while avoiding self-signed fallback risk.
- Private key lifecycle is isolated from Terraform state.

### Trade-offs

- Certificate issuance remains an explicit operational action in MVP.
- Edge availability depends on valid SSM certificate artifacts when strict mode is enabled.
- Renewal flow needs additional observability and alert plumbing (#532).

## Alternatives considered

1. **ACM + ALB/CloudFront managed termination**
   - Pros: managed renewals.
   - Cons: increased cost/complexity for MVP edge model.

2. **cert-manager inside k3s with DNS-01**
   - Pros: Kubernetes-native lifecycle.
   - Cons: extra cluster components and operational overhead for this scope.

3. **Store certificate/key directly in Terraform state or repository files**
   - Rejected for security reasons (secret exposure and persistence in state/history).

## Implementation status

- **Implemented**: #531 (issuance/deploy baseline + strict edge loading).
- **Planned**: #532 (renewal monitoring, alerting, controlled renewal path).

## Links

- Issue #531: https://github.com/ClementV78/CloudRadar/issues/531
- Issue #532: https://github.com/ClementV78/CloudRadar/issues/532
- Infrastructure doc: `docs/architecture/infrastructure.md`
- Bootstrap runbook: `docs/runbooks/bootstrap/terraform-backend-bootstrap.md`
