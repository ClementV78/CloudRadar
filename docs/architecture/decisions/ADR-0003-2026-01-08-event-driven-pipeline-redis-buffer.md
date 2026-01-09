# ADR-0003: Event-Driven Pipeline with Redis Buffer

## Status
Accepted

## Context
The platform needs an MVP-grade event pipeline to decouple ingestion from processing at low cost.

## Decision
Use a simple producer -> **Redis** -> consumer chain for event buffering and processing.

## Consequences
- Easy to deploy and cost-effective for MVP.
- Limited durability/ordering guarantees compared to Kafka.
- A future migration path to Kafka is preserved for v2.

## Details
- Deploy Redis in the `data` namespace via a ClusterIP service.
