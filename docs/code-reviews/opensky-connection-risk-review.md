# Code Review: OpenSky Connection Risk Review (Potential Gaps)

**Date:** 2026-02-23  
**Original reviewer:** Codex  
**Peer review:** GitHub Copilot (2026-02-23)  
**Scope:** `src/ingester` OpenSky token + states communication path

---

## Context

This note lists **potential reliability gaps** in the current OpenSky communication flow.  
It is intentionally short and prioritizes practical mitigation steps.

Current flow:
- `OpenSkyTokenService`: token fetch + cooldown/backoff (`synchronized getToken()`)
- `OpenSkyClient`: `/states/all` fetch + parsing + metrics
- `FlightIngestJob`: scheduler + ingestion backoff/disable logic

---

## Potential Gaps and Recommendations

| Priority | Potential gap | Why it matters | Recommendation (MVP) |
|---|---|---|---|
| P1 | No explicit HTTP timeouts on token/states requests | `AppConfig.httpClient()` calls `HttpClient.newHttpClient()` without `connectTimeout()`, and requests have no `.timeout()`. A network stall can block scheduler execution for too long. Combined with `synchronized getToken()`, this can create **scheduler starvation / prolonged blocking**. | Configure `HttpClient.connectTimeout()` and `HttpRequest.timeout()`; expose values via properties |
| P1 | `FetchResult` has no outcome field — root cause of gaps below | `FetchResult(List, Integer, Integer, Long)` cannot distinguish "0 flights in bbox" from "429 rate limit" from "500 server error" from "network exception". `FlightIngestJob` resets `consecutiveFailures` to 0 on any non-exception return, treating silent failures as successes. | Add `enum FetchOutcome { SUCCESS, RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, EXCEPTION }` to `FetchResult`. Let the job branch on outcome for backoff decisions. |
| P1 | Generic exceptions in states fetch are converted to empty success-like result | In `OpenSkyClient.fetchStates()`, the generic `catch (Exception)` returns `new FetchResult(List.of(), null, null, null)`. The job treats it as 0 flights and resets backoff. **Note:** `TokenRefreshException` is correctly re-thrown and does trigger backoff — the problem is limited to post-token errors (network, JSON parsing). | Throw a dedicated `FetchException` (or use the outcome enum above) so the job's existing backoff logic is consistently triggered. |
| P1 | HTTP `429` / `5xx` on states path return empty payload instead of explicit failure mode | Both return `new FetchResult(List.of(), remainingCredits, ...)` — same shape as success. **Partial mitigation for 429:** `remainingCredits` is passed back, and `updateRateLimit()` in the job slows polling when credits are low. **No mitigation for 5xx.** | Use `FetchOutcome` to classify. For 429: respect `X-Rate-Limit-Reset` header for pause duration. For 5xx: trigger immediate backoff. |
| P2 | No cached token invalidation on `401` | When `fetchStates()` receives a 401, the cached token in `OpenSkyTokenService` is **not invalidated**. It remains valid until natural expiry (15s margin). All subsequent cycles fail with 401 until the token happens to expire. | Add `tokenService.invalidateToken()` call on 401 response, then retry once. |
| P2 | No explicit single retry path for `401` after token refresh | Temporary server-side token invalidation causes an entire failed cycle with no recovery attempt. | On first `401`, invalidate token → force refresh → retry states request once → fail fast if still 401. |
| P2 | No metric to distinguish "0 flights legitimate" from "silent error" | `lastStatesCount` gauge shows 0 for both "empty bbox at night" and "OpenSky down returning errors silently". Grafana dashboards cannot alert on silent failures. | Add a gauge or counter for `ingester.fetch.empty_result.reason` with tags `{bbox_empty, error_suppressed, rate_limited}`. |
| P2 | No cross-pod jitter in polling schedule | `@Scheduled(fixedDelayString = "${ingester.refresh-ms}")` without jitter. Currently 1 replica, so theoretical. | Add small random jitter to polling delay or request timing if scaling to multiple replicas. |

---

## Design Hotspot (Summary)

The core design issue is that `FetchResult` is a **data-only record** with no status indicator:

```java
public record FetchResult(List<FlightState> states, Integer remainingCredits,
                          Integer creditLimit, Long resetAtEpochSeconds) {}
```

This forces `FlightIngestJob` to guess whether an empty list means real no-data or hidden failure.  
Adding an explicit outcome (`SUCCESS`, `RATE_LIMITED`, `SERVER_ERROR`, etc.) is the key simplification for backoff decisions and observability.

Scheduler nuance:
- `OpenSkyTokenService.getToken()` is `synchronized`.
- If HTTP has no timeout and one call stalls, scheduling can be heavily delayed.
- The “all scheduled tasks blocked” risk applies **only if using the default Spring single-thread scheduler pool**.

## Suggested Test Coverage Additions

- **Unit test**: `FetchResult` outcomes trigger expected job behavior (continue vs backoff).
- **Integration test**: token timeout and states timeout behavior triggers expected backoff.
- **Integration test**: `429` and `5xx` responses lead to controlled retry/backoff behavior.
- **Integration test**: `401` → token invalidation → forced refresh → single retry.
- **Load/stability smoke**: multi-client or multi-replica cadence does not create request bursts.

---

## Recommended Execution Order

1. Add explicit HTTP timeouts — lowest risk, immediate resilience gain (P1).
2. Add `FetchOutcome` to `FetchResult` — root cause fix enabling #3 and #4 (P1).
3. Make `FlightIngestJob` branch on outcome — backoff on error, continue on success only (P1).
4. Add `401` → invalidate token + single retry path (P2).
5. Add metric for empty-result classification (P2).
6. Add jitter if running more than one ingester replica (P2).

---

## Notes

- This is a **risk-oriented review**, not a full redesign proposal.
- Existing token cooldown work already improves resilience; this document focuses on the remaining connection-level reliability points.
- Peer review confirmed all 5 original gaps and added 3 more (FetchResult root cause, token invalidation, monitoring gap).
