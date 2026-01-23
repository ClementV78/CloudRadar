# ADR-0015: Redis List for Ingestion Queue (MVP)

Date: 2026-01-22
Status: Accepted

## Context
ADR-0003 established Redis as the MVP event buffer between ingestion and processing. The ingester now needs a concrete Redis data structure for the queue. We must balance delivery speed and operational simplicity against stronger delivery semantics.

## Decision
Use a Redis **List** (RPUSH + BLPOP/BRPOP) as the ingestion queue for MVP. Document Redis Streams as a v1.1+ improvement for consumer groups, acknowledgements, and replay.

## Options Considered
- **Redis List (chosen)**
  - Simple FIFO queue
  - Minimal implementation complexity
  - Sufficient for MVP demo
- **Redis Stream**
  - Consumer groups, acknowledgements, replay
  - Better delivery semantics but higher implementation complexity

## Consequences
- **Pros:** fastest delivery, lowest complexity, aligns with MVP scope.
- **Cons:** no acknowledgements or replay; at-most-once consumption risk.
- **Follow-up:** consider Redis Streams as a v1.1 improvement once the pipeline stabilizes.

## References
- ADR-0003: Event-Driven Pipeline with Redis Buffer
- Issue #111: OpenSky Ingester
- Issue #129: Dashboard API
