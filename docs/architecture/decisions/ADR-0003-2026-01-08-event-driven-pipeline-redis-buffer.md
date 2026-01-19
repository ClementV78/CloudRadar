# ADR-0003: Event-Driven Pipeline with Redis Buffer

## Status
Accepted

## Links
- Runbook: https://github.com/ClementV78/CloudRadar/blob/main/docs/runbooks/redis.md

## Context
The platform needs an MVP-grade event pipeline to decouple ingestion from processing at low cost.

## Options Considered

### Option A - Redis (buffer)
- Simple to deploy and operate on k3s.
- Low cost and good enough for MVP.

### Option B - Kafka (Strimzi)
- Strong durability and replay semantics.
- Higher operational complexity for MVP.

### Option C - RabbitMQ or NATS
- Good queue semantics and routing patterns.
- More moving parts than needed for MVP.

## Decision
Use a simple producer -> **Redis** -> consumer chain for event buffering and processing.

## Consequences
- Easy to deploy and cost-effective for MVP.
- Limited durability/ordering guarantees compared to Kafka.
- A future migration path to Kafka is preserved for v2.
- Single-replica Redis is a potential SPOF; acceptable for MVP.
- Redis uses the default `local-path` StorageClass for MVP; move to EBS CSI in v1.1 (issue #112).

## Details
- Deploy Redis in the `data` namespace via a ClusterIP service.
- StatefulSet with **1 replica** for MVP.
- PVC (5Gi) mounted at `/data` with AOF enabled (`--appendonly yes`).
- No authentication (internal-only access).
- Readiness: `redis-cli ping`; liveness: TCP 6379.
- Resource limits set to keep footprint small (requests 50m/128Mi, limits 250m/256Mi).
