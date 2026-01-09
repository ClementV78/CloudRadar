# ADR-0007: Edge with CloudFront + Nginx

## Status
Accepted

## Context
The platform needs a public edge with TLS and basic access control while keeping cost low.

## Decision
Use **CloudFront** in front of a public **Nginx** EC2 instance (TLS termination + Basic Auth) for the MVP.

## Consequences
- Improved latency via CDN cache.
- Simple, low-cost edge setup.
- Later evolution may include ACM/Route53 and internal NLB.

## Details
- Nginx reverse proxies to services in the private k3s subnet.
- Basic Auth protects the public entrypoint.
