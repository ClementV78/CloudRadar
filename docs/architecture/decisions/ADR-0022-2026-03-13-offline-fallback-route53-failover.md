# ADR-0022: Offline Fallback via Route53 Failover (No WAF in v1.1)

## Status
Accepted

## Context
CloudRadar runtime infrastructure can be intentionally destroyed for FinOps optimization. In that state, users currently hit an unavailable site instead of a branded fallback experience.

Constraints:
- keep the online runtime path unchanged (`edge` + Let's Encrypt cert loaded from SSM),
- preserve low recurring cost,
- provide a conversion-oriented contact path for demo requests,
- add anti-spam controls without introducing high fixed-cost components.

## Decision
Implement an offline fallback stack in the persistent bootstrap layer with Route53 failover:

Important DNS behavior:
- `PRIMARY` and `SECONDARY` are two failover records for the same public hostname (`cloudradar.<domain>`).
- Users always access the same URL; Route53 selects which target to return based on the primary health check.

1. **Primary DNS path (online)**
   - `cloudradar.<domain>` failover PRIMARY points to `live.<domain>` (existing edge path).
   - Primary health check probes `https://live.<domain>/statusz`.

2. **Secondary DNS path (offline)**
   - `cloudradar.<domain>` failover SECONDARY points to an offline CloudFront distribution.
   - CloudFront serves static assets from private S3 and routes `/api/contact-demo` to API Gateway.

3. **Contact form backend**
   - API Gateway HTTP API -> Lambda (arm64, non-VPC) -> SES email.
   - Anti-spam baseline: API throttling, honeypot, payload validation, DynamoDB rate limiting.

4. **Security/FinOps stance (v1.1)**
   - No WAF in v1.1 (fixed monthly cost avoided).
   - CloudWatch retention aligned with existing 3-7 day policy.
   - CloudFront `PriceClass_100` (EU + some US audience).

## Consequences
### Positive
- Branded offline UX remains available while live infra is down.
- Online architecture and existing edge TLS flow stay unchanged.
- Very low incremental cost for low traffic/demo volume.
- Contact conversion path remains functional during downtime.

### Trade-offs
- Offline fallback introduces additional DNS/TLS objects (Route53 failover + ACM for CloudFront fallback path).
- Route53 failover adds propagation and health-check detection delay.
- Without WAF, spam defense relies on application/API controls only.

## Rejected alternatives
1. **Single-domain CloudFront in front of online + offline**
   - Rejected for this iteration to avoid changing the online runtime path and certificate termination model.

2. **S3 website-only fallback**
   - Rejected because HTTPS + custom-domain behavior is insufficient for the main domain fallback requirement.

3. **WAF from day one**
   - Rejected for v1.1 due fixed cost/complexity; deferred unless abuse patterns justify it.

## Implementation links
- Issue: #576
- Runbook: `docs/runbooks/operations/offline-fallback.md`
- Infra doc: `docs/architecture/infrastructure.md`
