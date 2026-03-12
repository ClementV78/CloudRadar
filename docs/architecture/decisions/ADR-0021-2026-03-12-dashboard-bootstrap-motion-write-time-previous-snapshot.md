# ADR-0021: Dashboard Bootstrap Motion via Write-Time Previous Snapshot

Date: 2026-03-12
Status: Accepted

## Context

On first dashboard load, aircraft markers can remain static for about 10-15 seconds before movement starts.

Current map animation behavior requires two snapshots client-side (`N-1 -> N`), but the initial `GET /api/flights` response only exposes the latest position (`N`) for each aircraft. This creates a visible UX freeze at page startup.

We reject synthetic frontend extrapolation for bootstrap movement because it can produce incorrect positions.

We need a deterministic, data-driven solution that:
- preserves SRP and avoids new god-classes,
- keeps API backward-compatible,
- provides unit + integration testability,
- does not introduce significant performance regressions.

## Decision

Adopt **write-time previous snapshot modeling** in the processor-owned read model:

1. Processor writes both **current** and **previous** fields per `icao24` in `cloudradar:aircraft:last`.
2. Dashboard map response exposes optional previous fields (`prev_*`) with current fields.
3. Frontend uses previous->current for immediate animation on first load when previous data exists.
4. Existing batch refresh behavior remains unchanged for subsequent updates.

This makes bootstrap motion accurate without track fan-out reads or synthetic interpolation.

## Consequences

### Positive
- Removes startup freeze for aircraft that already have previous state.
- Keeps movement deterministic and data-backed.
- Moves state evolution to write-time (processor), keeping dashboard reads simple and fast.
- Avoids per-aircraft history reads at map load.

### Trade-offs
- Requires coordinated changes across processor + dashboard + frontend contracts.
- Adds payload fields (`prev_*`) and write-time update logic.
- Requires out-of-order event handling rules in processor.

## Alternatives Considered

1. **Read-time previous reconstruction in dashboard (Option 2)**
   - Pros: faster to implement initially.
   - Cons: higher read-time complexity/load, weaker consistency guarantees.

2. **Dedicated bootstrap endpoint (Option 3)**
   - Pros: avoids changing existing map endpoint payload.
   - Cons: extra endpoint complexity and duplicated map flow.

3. **SSE-enriched initial payload (Option 5)**
   - Pros: stream-centric bootstrap.
   - Cons: heavier stream semantics and more complex event handling.

4. **Frontend synthetic extrapolation (rejected)**
   - Rejected due to potential positional inaccuracies and UX trust issues.

## Implementation Guardrails

- Keep `FlightQueryService` orchestration-only (SRP).
- Make `prev_*` fields optional for backward compatibility.
- Add unit + integration tests for:
  - processor current/previous rotation,
  - out-of-order event handling,
  - dashboard mapping of `prev_*`,
  - frontend bootstrap animation path.
- Validate no meaningful perf regression on map read path and processing throughput.
- Maintain or improve test coverage on touched code paths.

## Links

- Issue: #570
- Related ADR: ADR-0019 (metadata enrichment read-model boundaries)
- Redis contract doc: `docs/events-schemas/redis-keys.md`
- Dashboard architecture doc: `docs/architecture/frontend-dashboard-technical-architecture.md`
