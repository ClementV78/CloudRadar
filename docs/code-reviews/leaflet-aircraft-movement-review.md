# Code Review: Leaflet Aircraft Movement — Static Markers Problem

**Date**: 2026-02-26  
**Scope**: `src/frontend/src/App.tsx` — map refresh, marker animation, and data lifecycle  
**Severity**: High — core UX feature (aircraft movement) is visually broken  

---

## 1. Executive Summary

Aircraft markers on the Leaflet map frequently appear static instead of smoothly moving between positions. The root cause is a combination of **animation cancellation by competing refresh triggers**, **extreme DOM churn from icon recreation on every frame**, and **lack of separation between data updates and visual animation**. The code has a well-designed interpolation system (`animateMarkers`, `lerpNumber`, `lerpHeading`) that is effectively short-circuited before it can complete.

---

## 2. Architecture Overview

### Data Pipeline (Backend → Frontend)

```
OpenSky API  →(~10s)→  Ingester  →(RPUSH)→  Redis Queue
                                                 ↓
                                            Processor
                                                 ↓ (HSET)
                                     Redis Hash (cloudradar:aircraft:last)
                                                 ↓
                              Dashboard API (poll + SSE stream)
                                                 ↓
                                       Frontend (React/Leaflet)
```

### Frontend Refresh Triggers

The frontend has **two concurrent refresh triggers** in the same `useEffect` (App.tsx L786-806):

| Trigger | Mechanism | Frequency |
|---------|-----------|-----------|
| **SSE stream** | `subscribeFlightUpdates()` → fires on `batch-update` event | ~every 10s (aligned with ingester cycle) |
| **Timer** | `setInterval(refreshDataRef.current, REFRESH_INTERVAL_MS)` | every 10s (fixed) |

Both call `refreshDataRef.current()` → `refreshData()` → `performRefreshCycle()`.

### Animation System

The interpolation pipeline in `animateMarkers()` (App.tsx L443-472):

1. Captures current `mapFlightsRef.current` as start positions
2. Builds a `startByIcao` map for O(1) lookup
3. Uses `requestAnimationFrame` loop to interpolate positions
4. Calls `setMapFlights()` on each frame → triggers React re-render → Leaflet updates markers

---

## 3. Identified Problems

### P1 — CRITICAL: Timer Cancels SSE-triggered Animation

**Location**: `performRefreshCycle()` (App.tsx L550-565)

```tsx
if (!mapFlightsRef.current.length) {
  cancelMarkerAnimation();
  setMapFlights(normalizedFlights);
} else if (batchChanged) {
  // ✅ Animation starts here
  animateMarkers(normalizedFlights, resolveAnimationDurationMs(batchEpoch, previousBatchEpoch));
} else {
  // ❌ Animation is KILLED here
  cancelMarkerAnimation();
  setMapFlights(normalizedFlights);
}
```

**Scenario**:
1. `t=0s` — SSE fires `batch-update`, `batchChanged=true` → animation starts with duration ~10s
2. `t=2s` — Timer fires, fetches same batch from API → `batchChanged=false`
3. → `cancelMarkerAnimation()` is called → **animation cancelled after only 2s** (20% progress)
4. → `setMapFlights(normalizedFlights)` snaps all markers to final positions instantly

**Result**: Aircraft appear to teleport or not move at all (if the delta is small at the current zoom level).

**Why it's frequent**: The timer and SSE are unsynchronized. With both running at ~10s intervals, they frequently overlap. The SSE notification arrives when new data is ready, and the timer fires independently. Any time they overlap, the animation is interrupted.

### P2 — CRITICAL: Icon DOM Replacement on Every Animation Frame

**Location**: Render loop (App.tsx L948-970)

```tsx
{mapFlights.map((flight) => {
  // ...
  return (
    <Marker
      key={flight.icao24}
      position={[flight.lat as number, flight.lon as number]}
      icon={markerIcon(flight, selected, mapZoom, isStatic)}
      // ...
    />
  );
})}
```

**Problem**: `markerIcon()` creates a **new `L.divIcon()` instance** on every render. During animation, `setMapFlights()` fires via `requestAnimationFrame` (60fps target), triggering a React re-render for each frame. For each of the N markers, a new DivIcon is created with new HTML (because `heading` changes per frame via interpolation):

```tsx
// Inside markerIcon() - heading is baked into the HTML string
html: `<div class="aircraft-rotator" style="transform: rotate(${heading}deg)">`
```

React-Leaflet detects icon prop change (new object reference) → calls Leaflet `marker.setIcon()` → Leaflet **removes and recreates the entire icon DOM element**.

**Performance impact**: With 200 aircraft × 60fps = **12,000 DOM element replacements per second**. This causes:
- Massive frame drops, making animation appear jerky or frozen
- High GC pressure from thousands of short-lived DivIcon + DOM objects
- Potential browser tab unresponsiveness

### P3 — HIGH: Coalesce Drain Loop Cancels Animation

**Location**: `drainQueuedRefreshes()` (App.tsx L614-620)

```tsx
const drainQueuedRefreshes = useCallback(async () => {
  do {
    refreshQueuedRef.current = false;
    await performRefreshCycle();
  } while (refreshQueuedRef.current && isMountedRef.current);
}, [performRefreshCycle]);
```

When a refresh is coalesced (timer fires while SSE refresh is in-flight), the drain loop runs **an additional cycle** after the first one completes. This second cycle:
1. Fetches the same batch (no new data yet)
2. Finds `batchChanged=false`
3. Cancels any animation that the first cycle started

This makes animation cancellation even more likely than P1 alone.

### P4 — MEDIUM: No Icon Memoization

**Problem**: Even outside animation frames, every React re-render (state change from clock, detail panel, etc.) recreates all icons. The `markerIcon()` function:
- Has no caching
- Returns a new `L.divIcon()` object every time
- Is called for every marker on every render

Any state change (UTC clock updates every 1s, detail panel open/close, ingester status refresh, etc.) triggers a full icon rebuild for all markers.

### P5 — MEDIUM: Zoom Interaction Drops Animation

When the user zooms, `setMapZoom()` triggers a re-render. The `markerIcon` parameters change (zoom affects scale), so all icons are rebuilt. Any in-progress animation continues but with a potentially jarring icon swap mid-animation. Additionally, zooming generates multiple `zoomend` events that trigger re-renders while animation is running.

### P6 — LOW: Detail Panel Open/Close Recreates Refresh Chain

`selectedIcao24` is in the dependency array of `performRefreshCycle`. Every select/deselect recreates `performRefreshCycle` → `drainQueuedRefreshes` → `refreshData`. While `refreshDataRef` ensures the latest version is always used, the cascade recreation is unnecessary cognitive complexity.

### P7 — LOW: First Paint After Page Load Has No Animation

On page load, `previousSnapshotPositionsRef` is empty and `mapFlightsRef.current` is empty. The first batch hits the `!mapFlightsRef.current.length` branch and snaps. This is correct behavior, but subsequent batches may also snap if P1/P3 kill the animation.

---

## 4. Root Cause Hierarchy

```
Aircraft appear static
├── Animation is cancelled prematurely (P1, P3)
│   ├── Timer and SSE are unsynchronized
│   ├── Non-batch refreshes cancel in-progress animation
│   └── Drain loop runs extra cycle with same batch
├── Animation frames are too expensive to render (P2, P4)
│   ├── New DivIcon per marker per frame
│   ├── Heading baked into icon HTML → DOM replacement
│   └── No icon memoization at all
└── Edge cases compound the problem (P5, P6)
    ├── Zoom triggers icon rebuild during animation
    └── Detail panel interactions cascade refresh chain
```

---

## 5. Proposed Solution

### Architecture Change: Separate Animation from Data Fetching

The core principle: **data refresh and visual animation must be decoupled**. Animation should run independently between two known states, immune to subsequent data fetches that return the same batch.

#### S1 — Guard Animation Against Non-Batch Refreshes

**Fix P1 + P3**: When `batchChanged=false` and an animation is already running, **do not cancel it**.

```tsx
// BEFORE (current code)
} else {
  cancelMarkerAnimation();
  setMapFlights(normalizedFlights);
}

// AFTER (proposed)
} else if (animationFrameRef.current === null) {
  // No animation running, safe to snap (e.g., first load, error recovery)
  setMapFlights(normalizedFlights);
}
// else: animation in progress for same batch — let it finish
```

This is the single highest-impact fix. No animation will be cancelled by a redundant refresh.

#### S2 — Move Heading Rotation Out of Icon HTML

**Fix P2 core**: The heading rotation should NOT be part of the DivIcon HTML. Instead, rotate via direct DOM manipulation after render:

```tsx
// Option A: Use a ref-based approach to rotate the marker's DOM element
// The icon HTML stays static (no rotation in template):
html: `<div class="aircraft-marker ...">
         <div class="aircraft-rotator">${glyph}</div>
       </div>`

// After marker is rendered, rotate via CSS on the DOM element:
markerElement.style.transform = `rotate(${heading}deg)`;
```

**Implementation approach**: Create a custom React component wrapping `<Marker>` that:
1. Creates the DivIcon only when shape/color/size/selected/zoom changes (NOT on heading change)
2. After Leaflet renders the icon, grabs the `.aircraft-rotator` DOM element
3. Updates `transform: rotate(...)` directly via `element.style.transform`
4. Updates position via `marker.setLatLng()` directly (bypassing React re-render)

```tsx
// Proposed: AnimatedMarker component
function AnimatedMarker({ flight, selected, zoom, isStatic, onSelect }: Props) {
  const markerRef = useRef<L.Marker>(null);
  
  // Memoize icon — only recreate when visual identity changes
  const icon = useMemo(
    () => markerIconStatic(flight, selected, zoom, isStatic),
    [flight.fleetType, flight.militaryHint, flight.aircraftSize, 
     flight.airframeType, selected, zoom, isStatic]
  );

  // Update position and rotation via direct DOM manipulation
  useEffect(() => {
    const marker = markerRef.current;
    if (!marker || flight.lat == null || flight.lon == null) return;
    
    marker.setLatLng([flight.lat, flight.lon]);
    
    const el = marker.getElement()?.querySelector('.aircraft-rotator') as HTMLElement;
    if (el) {
      el.style.transform = `rotate(${flight.heading ?? 0}deg)`;
    }
  }, [flight.lat, flight.lon, flight.heading]);

  return (
    <Marker ref={markerRef} position={[flight.lat!, flight.lon!]} icon={icon} ... />
  );
}
```

This reduces DOM operations from **N markers × 60fps** to **N markers × icon-change-frequency** (near zero during animation).

#### S3 — Use requestAnimationFrame Without React State

**Fix P2 amplifier**: The animation loop should NOT call `setMapFlights()` on every frame. Instead:

1. Store target and start positions in refs
2. On each `requestAnimationFrame`, calculate interpolated positions
3. Update Leaflet markers **directly** via `marker.setLatLng()` and DOM rotation
4. Only call `setMapFlights()` once at animation end (to sync React state)

```tsx
const animateMarkers = useCallback(
  (targetFlights: FlightMapItem[], durationMs: number) => {
    cancelMarkerAnimation();
    
    const startFlights = mapFlightsRef.current;
    const startByIcao = new Map(startFlights.map(f => [f.icao24, f]));
    const markersByIcao = markerRefsMap.current; // Map<string, L.Marker>
    const animStart = performance.now();

    const tick = (now: number) => {
      const progress = Math.min(1, (now - animStart) / durationMs);
      
      for (const target of targetFlights) {
        const start = startByIcao.get(target.icao24);
        const marker = markersByIcao.get(target.icao24);
        if (!marker || !start || !canInterpolateFlight(start, target)) continue;
        
        const interpolated = interpolateFlight(start, target, progress);
        // Direct Leaflet API — NO React re-render
        marker.setLatLng([interpolated.lat!, interpolated.lon!]);
        const rotator = marker.getElement()?.querySelector('.aircraft-rotator');
        if (rotator) {
          (rotator as HTMLElement).style.transform = 
            `rotate(${interpolated.heading ?? 0}deg)`;
        }
      }
      
      if (progress < 1) {
        animationFrameRef.current = requestAnimationFrame(tick);
      } else {
        animationFrameRef.current = null;
        // Sync React state only at the end
        setMapFlights(targetFlights);
      }
    };
    
    animationFrameRef.current = requestAnimationFrame(tick);
  },
  [cancelMarkerAnimation]
);
```

#### S4 — Memoize Static Icon Creation

**Fix P4**: Cache DivIcon instances to avoid recreating identical icons.

```tsx
const iconCache = new Map<string, DivIcon>();

function getCachedIcon(
  flight: FlightMapItem, selected: boolean, zoom: number, isStatic: boolean
): DivIcon {
  // Key based on visual-identity fields only (NOT lat/lon/heading)
  const key = `${flight.fleetType}-${flight.militaryHint}-${flight.aircraftSize}`
    + `-${flight.airframeType}-${selected}-${zoom}-${isStatic}`;
  
  let icon = iconCache.get(key);
  if (!icon) {
    icon = markerIconStatic(flight, selected, zoom, isStatic);
    iconCache.set(key, icon);
  }
  return icon;
}
```

#### S5 — Deduplicate Refresh Triggers

**Fix P1 root cause**: Instead of running two independent triggers, make the timer a **fallback** that only fires when SSE is silent:

```tsx
useEffect(() => {
  void refreshDataRef.current();
  
  let refreshTimer: number | null = null;
  
  const scheduleNextPoll = () => {
    if (refreshTimer !== null) clearTimeout(refreshTimer);
    refreshTimer = window.setTimeout(() => {
      void refreshDataRef.current();
      scheduleNextPoll();
    }, REFRESH_INTERVAL_MS);
  };
  
  scheduleNextPoll();
  
  const unsubscribeStream = subscribeFlightUpdates(
    (event) => {
      if (
        event.latestOpenSkyBatchEpoch === null
        || event.latestOpenSkyBatchEpoch !== lastBatchEpochRef.current
      ) {
        void refreshDataRef.current();
        // Reset timer — SSE already triggered the refresh
        scheduleNextPoll();
      }
    },
    () => {
      setApiStatus(prev => prev === 'online' ? 'degraded' : prev);
    }
  );

  return () => {
    unsubscribeStream();
    if (refreshTimer !== null) clearTimeout(refreshTimer);
  };
}, []);
```

This ensures only **one** refresh per batch, eliminating the race condition entirely.

---

## 6. Edge Cases Addressed

| Scenario | Current Behavior | Proposed Behavior |
|----------|-----------------|-------------------|
| **Page reload** | First paint snaps (correct), subsequent animation often killed | First paint snaps, subsequent animations complete fully |
| **Zoom** | Icons rebuilt during animation, potential jank | Icons memoized, rotation via DOM, zoom doesn't break animation |
| **Click on aircraft** | `selectedIcao24` change cascades refresh chain rebuild | Decouple detail loading from refresh cycle, use refs for selection |
| **Close detail modal** | Same cascade as click | Same fix as click |
| **SSE + Timer race** | Animation cancelled ~50% of the time | Single deduplicated trigger, animation never cancelled by stale poll |
| **SSE disconnection** | Timer keeps running (correct), but animation still fragile | Timer serves as reliable fallback, animation protected |
| **Rapid batch updates** | Drain loop may cancel animation | Guard ensures running animation isn't cancelled for same batch |

---

## 7. Implementation Priority

| Priority | Fix | Impact | Effort |
|----------|-----|--------|--------|
| **1** | S1 — Guard animation against non-batch cancellation | Fixes visible static markers immediately | ~15 min |
| **2** | S5 — Deduplicate refresh triggers | Eliminates root cause of timer/SSE race | ~30 min |
| **3** | S2 + S3 — Direct DOM manipulation for animation | Fixes performance, enables smooth 60fps | ~2-3h |
| **4** | S4 — Icon memoization | Reduces render cost for all re-renders | ~30 min |

**Recommendation**: Start with S1 + S5 (quick wins, high impact). Then S2 + S3 as a follow-up for production-quality animation. S4 is a good general performance improvement.

---

## 8. Affected Files

| File | Changes |
|------|---------|
| `src/frontend/src/App.tsx` | Animation logic, refresh cycle, marker rendering |
| `src/frontend/src/components/AnimatedMarker.tsx` | New component (proposed for S2+S3) |
| `src/frontend/src/aircraftVisuals.ts` | Separate icon creation from heading rotation |

---

## 9. Test Scenarios for Validation

1. **Baseline**: Open map, observe aircraft movement over 60s — markers should visibly move between positions
2. **Zoom**: Zoom in/out during active animation — markers should continue moving smoothly
3. **Select flight**: Click a marker during animation — animation should continue for other markers
4. **Close detail**: Close detail panel — markers should continue moving
5. **Page reload**: Reload, wait 20s — second batch should show smooth movement
6. **Network jitter**: Throttle network in DevTools — timer fallback should maintain updates
7. **Performance**: Open DevTools Performance tab — no excessive DOM operations during animation
