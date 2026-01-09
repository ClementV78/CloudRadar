# ADR-0001: AWS Region us-east-1

## Status
Accepted

## Context
We need a default AWS region for the MVP that optimizes cost while keeping latency acceptable.

## Decision
Use **us-east-1** as the default AWS region. Use CloudFront to mitigate end-user latency.

## Consequences
- Lower baseline cost compared to some EU regions.
- CloudFront becomes part of the edge strategy for latency optimization.
- All infra defaults and docs should reference us-east-1.
