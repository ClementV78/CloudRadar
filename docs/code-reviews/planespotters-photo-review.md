# Code Review: Planespotters Photo Cache Integration

**PR**: [#488](https://github.com/ClementV78/CloudRadar/pull/488) feat(app): add planespotters photo cache with redis persistence  
**Issue**: [#485](https://github.com/ClementV78/CloudRadar/issues/485)  
**Branch**: `feat/485-photo-cache-redis-persistence`  
**Scope**: v1.1  
**Reviewer**: Copilot  
**Review Date**: 2026-02-23

---

## 1. Executive Summary

This PR integrates the [Planespotters.net](https://www.planespotters.net/) public photo API into the dashboard detail flow. When a user selects an aircraft, the backend fetches (or cache-hits) a photo thumbnail and returns it in the `GET /api/flights/{icao24}` payload. The frontend renders the thumbnail in the detail panel with a click-to-expand lightbox for the large image.

The implementation covers backend service, Redis caching with TTL, distributed rate limiting, frontend UI, comprehensive tests, and documentation updates (API doc, architecture, runbooks).

**Changeset**: 25 files, +916 / −59 lines (2 commits).

**Overall Assessment**: ✅ **APPROVED with minor recommendations**

---

## 2. Architecture & Design

### 2.1 Integration Pattern ✅

The integration follows a clean cache-aside pattern:

1. Check Redis cache (`cloudradar:photo:v1:icao24:<icao24>`)
2. On miss → call Planespotters API (hex lookup first, registration fallback)
3. Cache result with status-dependent TTL
4. Return photo metadata in detail response DTO

This is a sound pattern: the external API is never called on the hot path when the cache is warm, and it degrades gracefully when rate-limited or unavailable.

### 2.2 Rate Limiting ✅

Global distributed rate limiter implemented via Redis `INCR` + `EXPIRE` on a per-second key (`cloudradar:photo:v1:ratelimit:sec:<epochSecond>`). Default 2 rps.

**Strengths**:
- Simple, stateless, works across multiple pod replicas
- Configurable via environment variable (`PLANESPOTTERS_GLOBAL_RPS`)

**Minor concern**: The `INCR` + `EXPIRE` are two separate Redis operations (not atomic). In theory, if a crash occurs between them, the key could persist without TTL. In practice, the 2-second TTL is short enough and the `count == 1L` guard ensures only the first caller sets expiry. This is acceptable for MVP scope.

### 2.3 Feature Toggle ✅

The entire integration is behind `planespotters.enabled` (default `true`). When disabled, `resolvePhoto()` returns `null`, cleanly omitting the photo block from the JSON response. Good zero-cost off switch.

### 2.4 Resilience ✅

- Upstream errors, timeouts, parse failures → cached as `error` status with short TTL (120s)
- Rate-limited results → cached with very short TTL (5s) to avoid thundering herd
- Negative results (`not_found`) → cached 6h to avoid hammering the API
- Available results → cached 7 days (reasonable for aircraft photos that rarely change)
- `InterruptedException` properly re-interrupts the thread
- Photo resolution never blocks or fails the core detail endpoint

---

## 3. Backend Implementation

### 3.1 `PlanespottersPhotoService` ✅

**File**: `src/dashboard/src/main/java/com/cloudradar/dashboard/service/PlanespottersPhotoService.java` (+285 lines)

Well-structured service with clear single responsibility. Key observations:

- **Hex-first with registration fallback**: smart strategy that maximizes hit rate since ICAO24 hex is always available but registration only from enrichment
- **URL sanitization**: `sanitizeUrl()` validates HTTPS scheme and whitelists `TRUSTED_HOSTS` — good security practice to avoid serving arbitrary URLs to the frontend
- **Metrics**: 7 Micrometer counters cover cache hits/misses, limiter rejects, and upstream outcomes — excellent observability for a new integration
- **Constructor injection with test-friendly override**: public constructor uses default `HttpClient`, package-private constructor accepts injected client for testing

**Recommendations**:

1. **Logging**: The service has no `Logger` — consider adding `warn`-level logging for upstream errors to aid debugging in production. Silent failures (especially `IOException`) can be hard to diagnose from metrics alone.

2. **`Math.max()` guards on TTL/timeout**: Defensive but might hide misconfiguration. A `@PostConstruct` validation or `@Min` annotation on properties would surface bad values earlier.

### 3.2 `FlightPhoto` Record ✅

**File**: `src/dashboard/src/main/java/com/cloudradar/dashboard/model/FlightPhoto.java` (+59 lines)

Clean Java record with factory methods for each status. Well-documented Javadoc.

The `status` field is a plain `String` rather than an enum — this is acceptable for JSON serialization simplicity, but the lack of compile-time safety means a typo in status comparison (`"availble"`) would silently fail. Consider package-private constants for the status values (optional, low priority).

### 3.3 `DashboardProperties.Planespotters` ✅

**File**: `src/dashboard/src/main/java/com/cloudradar/dashboard/config/DashboardProperties.java` (+90 lines)

Follows the established pattern of the other inner config classes. All values externalized via environment variables in `application.yml`.

**TTL defaults are sensible**:
| Status | TTL | Rationale |
|---|---|---|
| `available` | 7 days | Photos rarely change |
| `not_found` | 6 hours | Avoid repeated lookups for unknown aircraft |
| `error` | 2 minutes | Allow quick retry |
| `rate_limited` | 5 seconds | Brief backoff |

### 3.4 `FlightQueryService` Integration ✅

**File**: `src/dashboard/src/main/java/com/cloudradar/dashboard/service/FlightQueryService.java` (+10 / −1 lines)

Minimal, surgical change. The photo service is injected as `Optional<PlanespottersPhotoService>` following the existing pattern for optional dependencies (`aircraftRepo`, `prometheusMetricsService`). Photo resolution occurs only in the detail path, not in list or metrics.

---

## 4. Frontend Implementation

### 4.1 `DetailPanel.tsx` ✅

**File**: `src/frontend/src/components/DetailPanel.tsx` (+121 / −39 lines)

- Thumbnail rendered inside detail panel with click-to-open lightbox
- Lightbox state reset on aircraft change via `useEffect` on `detail?.icao24`
- Graceful degradation: human-readable status messages for `not_found`, `rate_limited`, `error`
- `loading="lazy"` attribute on images — appropriate for off-screen content
- Accessibility: `role="dialog"`, `aria-modal`, `aria-label` on the lightbox

**Recommendations**:

1. **Keyboard dismiss**: The lightbox modal can be closed by clicking the backdrop or the "close" button, but there is no `Escape` key handler. Consider adding a `useEffect` with `keydown` listener for better UX.

2. **Image error handling**: If the thumbnail URL is cached but the CDN image has been removed, the `<img>` will show a broken image. An `onError` fallback (hide the image or show placeholder) would improve robustness.

### 4.2 `styles.css` ✅

**File**: `src/frontend/src/styles.css` (+93 lines)

- Follows the existing design system (glass-panel, `var(--accent)`, `var(--muted)`)
- Modal styling is clean with proper z-index management (`z-index: 900`)
- Responsive with `width: min(560px, calc(100vw - 36px))`
- No unused selectors introduced

### 4.3 `types.ts` ✅

**File**: `src/frontend/src/types.ts` (+13 lines)

`FlightPhoto` interface mirrors the backend record exactly. `photo` field added as nullable to `FlightDetailResponse`.

---

## 5. Testing ✅

### 5.1 `PlanespottersPhotoServiceTest` ✅ (+128 lines)

Covers four key scenarios:
1. Cache hit → returns cached payload, no HTTP call
2. Rate limiter rejection → returns `rate_limited`, no HTTP call
3. Cache miss → fetches, parses, caches with correct TTL
4. Disabled integration → returns `null`

**Good**: Mocks `HttpClient` at the network boundary (not `WebClient`/`RestTemplate`), keeping tests fast and deterministic.

**Recommendation**: Consider adding edge case tests:
- Upstream returns 429 → `rate_limited` status
- Upstream returns malformed JSON → `error` status
- Registration fallback path (hex returns not_found, reg returns available)

### 5.2 `FlightQueryServiceTest` Updates ✅ (+47 / −8 lines)

All existing tests updated to pass the new `Optional<PlanespottersPhotoService>` parameter. The detail test now verifies photo resolution is called with correct `icao24` and `registration`, and asserts the response contains the expected photo data.

### 5.3 `DashboardControllerTest` ✅ (+1 line)

Minimal update: added `null` for the `photo` field in the mock `FlightDetailResponse` constructor.

---

## 6. Documentation ✅

### 6.1 API Documentation ✅
- `docs/api/dashboard-api.md`: example response updated with `photo` block, status semantics documented
- Clear explanation of the four status values and frontend loading strategy

### 6.2 Architecture Documentation ✅
- `docs/architecture/application-architecture.md`: two new Redis key patterns documented
- `docs/architecture/frontend-dashboard-technical-architecture.md`: Mermaid diagram updated with `PlanespottersPhotoService`, Redis photo namespace, and Planespotters API node
- Detail flow steps updated (4 → 5 steps)

### 6.3 Runbooks ✅
- `docs/runbooks/ci-cd/ci-infra.md`: backup/restore scope clarified to include photo cache namespace
- `docs/runbooks/operations/redis.md`: explicit note about photo cache inclusion in volume-level backup

### 6.4 Frontend README ✅
- Architecture Mermaid updated (API → Redis photo link)
- Runtime model updated with photo thumbnail and lazy large-image loading
- DetailPanel description updated

---

## 7. Version Bump ✅

All six k8s deployment manifests and `VERSION` file bumped to `0.1.39`. Consistent across all services.

---

## 8. Summary of Recommendations

| # | Priority | Area | Recommendation |
|---|---|---|---|
| 1 | Low | Backend | Add `warn`-level logging in `PlanespottersPhotoService` for upstream errors |
| 2 | Low | Backend | Consider property validation (`@Min`/`@PostConstruct`) instead of `Math.max()` guards |
| 3 | Low | Backend | Add string constants for photo status values to prevent typos |
| 4 | Low | Frontend | Add `Escape` key handler to close the lightbox modal |
| 5 | Low | Frontend | Add `onError` handler on `<img>` for broken CDN URLs |
| 6 | Low | Tests | Add edge case tests (429, malformed JSON, registration fallback) |

None of these block merge. All are optional improvements that can be addressed in a follow-up.

---

## 9. Security Checklist

- ✅ No secrets or credentials introduced
- ✅ External URLs validated (HTTPS-only, host whitelist)
- ✅ No user input reflected unsanitized in responses
- ✅ `rel="noreferrer"` on external link
- ✅ Rate limiter prevents abuse of upstream API
- ✅ Feature toggle allows disabling integration without redeploy (env var)

---

## 10. Verdict

**✅ APPROVED** — Clean, well-documented integration with good resilience patterns, proper caching, security validation, and comprehensive test coverage. The photo feature adds genuine value to the portfolio demo (visual wow factor) with minimal risk to the core flight data path.
