import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { MapContainer, Marker, Polyline, Popup, Rectangle, TileLayer, useMapEvents } from 'react-leaflet';
import type { Marker as LeafletMarker } from 'leaflet';

import {
  fetchBboxBoostStatus,
  fetchFlightDetail,
  fetchFlights,
  fetchIngesterScale,
  fetchIngesterScalePublic,
  fetchMetrics,
  scaleIngester,
  subscribeFlightUpdates,
  triggerBboxBoost
} from './api';
import { IDF_BBOX, MAP_MAX_BOUNDS, REFRESH_INTERVAL_MS, STALE_AFTER_SECONDS } from './constants';
import { DetailPanel } from './components/DetailPanel';
import { Header } from './components/Header';
import { KpiStrip } from './components/KpiStrip';
import type { KpiTab } from './components/KpiStrip';
import { MapLegend } from './components/MapLegend';
import { createRefreshWatchdog, resolveSnapshotUpdateAction, shouldRefreshFromStreamEvent } from './mapRefresh';
import { createMarkerIconResolver } from './markerIcons';
import type {
  ApiStatus,
  Bbox,
  BboxBoostStatusResponse,
  FlightDetailResponse,
  FlightMapItem,
  FlightsMetricsResponse,
  OpenSkyStatus
} from './types';

const MAP_CENTER: [number, number] = [
  (IDF_BBOX.minLat + IDF_BBOX.maxLat) / 2,
  (IDF_BBOX.minLon + IDF_BBOX.maxLon) / 2
];

const MAX_BOUNDS: [[number, number], [number, number]] = [
  [MAP_MAX_BOUNDS.minLat, MAP_MAX_BOUNDS.minLon],
  [MAP_MAX_BOUNDS.maxLat, MAP_MAX_BOUNDS.maxLon]
];

const TRACK_SEGMENT_GAP_SECONDS = 15 * 60;
const CURRENT_TRACK_FALLBACK_WINDOW_SECONDS = 6 * 60 * 60;
const MIN_INTERPOLATION_DURATION_MS = 1_000;
const MAX_INTERPOLATION_DURATION_MS = 20_000;
const MAX_INTERPOLATION_LAST_SEEN_GAP_SECONDS = 180;
const MAX_INTERPOLATION_DISTANCE_KM = 200;
const STATIC_POSITION_THRESHOLD_KM = 0.05;
const INGESTER_TOGGLE_POLL_INTERVAL_MS = 1_000;
const INGESTER_TOGGLE_TIMEOUT_MS = 30_000;
const INGESTER_STATUS_REFRESH_MS = 30_000;
const INGESTER_INITIAL_RETRY_DELAYS_MS = [0, 1_500, 4_000];
const KNOT_TO_KMH = 1.852;
const EDGE_AUTH_STORAGE_KEY = 'cloudradar.edge.basic_auth';

interface EdgeCredentials {
  username: string;
  password: string;
}

function formatUtcClock(date: Date): string {
  const text = date.toISOString();
  return text.slice(11, 19);
}

function formatRefreshedAt(iso: string | null): string | null {
  if (!iso) {
    return null;
  }
  return iso.replace('T', ' ').replace('Z', '');
}

function formatSpeedKmh(speedKt: number | null): string {
  if (!isFiniteNumber(speedKt)) {
    return 'n/a';
  }
  return `${(speedKt * KNOT_TO_KMH).toFixed(1)} km/h`;
}

function waitMs(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

function latestLastSeen(flights: FlightMapItem[]): number | null {
  const values = flights
    .map((flight) => flight.lastSeen)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  if (!values.length) {
    return null;
  }
  return Math.max(...values);
}

function computeOpenSkyStatus(flights: FlightMapItem[]): OpenSkyStatus {
  const latestEpoch = latestLastSeen(flights);
  if (!latestEpoch) {
    return 'unknown';
  }

  const age = Math.floor(Date.now() / 1000) - latestEpoch;
  return age <= STALE_AFTER_SECONDS ? 'online' : 'stale';
}

function isFiniteNumber(value: number | null): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function haversineKm(startLat: number, startLon: number, endLat: number, endLon: number): number {
  const toRad = (value: number): number => (value * Math.PI) / 180;
  const dLat = toRad(endLat - startLat);
  const dLon = toRad(endLon - startLon);
  const lat1 = toRad(startLat);
  const lat2 = toRad(endLat);

  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return 6371 * c;
}

function lerpNumber(start: number, end: number, progress: number): number {
  return start + (end - start) * progress;
}

function lerpNullableNumber(start: number | null, end: number | null, progress: number): number | null {
  if (isFiniteNumber(start) && isFiniteNumber(end)) {
    return lerpNumber(start, end, progress);
  }
  return end;
}

function lerpHeading(start: number | null, end: number | null, progress: number): number | null {
  if (!isFiniteNumber(start) || !isFiniteNumber(end)) {
    return end;
  }

  let delta = ((end - start + 540) % 360) - 180;
  if (!Number.isFinite(delta)) {
    delta = 0;
  }

  const next = start + delta * progress;
  return ((next % 360) + 360) % 360;
}

function normalizeFlights(items: FlightMapItem[]): FlightMapItem[] {
  const deduped = new Map<string, FlightMapItem>();

  for (const item of items) {
    if (item.lat === null || item.lon === null) {
      continue;
    }

    const icao24 = item.icao24.trim().toLowerCase();
    if (!icao24) {
      continue;
    }

    const candidate: FlightMapItem = {
      ...item,
      icao24
    };
    const existing = deduped.get(icao24);
    if (!existing) {
      deduped.set(icao24, candidate);
      continue;
    }

    const existingLastSeen = typeof existing.lastSeen === 'number' ? existing.lastSeen : Number.NEGATIVE_INFINITY;
    const candidateLastSeen = typeof candidate.lastSeen === 'number' ? candidate.lastSeen : Number.NEGATIVE_INFINITY;
    if (candidateLastSeen >= existingLastSeen) {
      deduped.set(icao24, candidate);
    }
  }

  return Array.from(deduped.values());
}

function toRectangleBounds(bbox: Bbox): [[number, number], [number, number]] {
  return [
    [bbox.minLat, bbox.minLon],
    [bbox.maxLat, bbox.maxLon]
  ];
}

function sameBbox(left: Bbox, right: Bbox): boolean {
  return left.minLon === right.minLon
    && left.minLat === right.minLat
    && left.maxLon === right.maxLon
    && left.maxLat === right.maxLat;
}

function resolveAnimationDurationMs(latestBatchEpoch: number | null, previousBatchEpoch: number | null): number {
  if (
    isFiniteNumber(latestBatchEpoch)
    && isFiniteNumber(previousBatchEpoch)
    && latestBatchEpoch > previousBatchEpoch
  ) {
    const measuredDeltaMs = (latestBatchEpoch - previousBatchEpoch) * 1000;
    return Math.max(MIN_INTERPOLATION_DURATION_MS, Math.min(MAX_INTERPOLATION_DURATION_MS, measuredDeltaMs));
  }

  return Math.max(MIN_INTERPOLATION_DURATION_MS, Math.min(MAX_INTERPOLATION_DURATION_MS, REFRESH_INTERVAL_MS));
}

function canInterpolateFlight(start: FlightMapItem, end: FlightMapItem): boolean {
  if (
    !isFiniteNumber(start.lat) || !isFiniteNumber(start.lon)
    || !isFiniteNumber(end.lat) || !isFiniteNumber(end.lon)
  ) {
    return false;
  }

  if (isFiniteNumber(start.lastSeen) && isFiniteNumber(end.lastSeen)) {
    if (end.lastSeen < start.lastSeen) {
      return false;
    }
    if (Math.abs(end.lastSeen - start.lastSeen) > MAX_INTERPOLATION_LAST_SEEN_GAP_SECONDS) {
      return false;
    }
  }

  const distanceKm = haversineKm(start.lat, start.lon, end.lat, end.lon);
  if (distanceKm > MAX_INTERPOLATION_DISTANCE_KM) {
    return false;
  }

  return true;
}

function interpolateFlight(start: FlightMapItem, end: FlightMapItem, progress: number): FlightMapItem {
  return {
    ...end,
    lat: lerpNullableNumber(start.lat, end.lat, progress),
    lon: lerpNullableNumber(start.lon, end.lon, progress),
    heading: lerpHeading(start.heading, end.heading, progress),
    speed: lerpNullableNumber(start.speed, end.speed, progress),
    altitude: lerpNullableNumber(start.altitude, end.altitude, progress)
  };
}

interface ClassifiedTrackSegments {
  current: Array<Array<[number, number]>>;
  historical: Array<Array<[number, number]>>;
}

interface NormalizedTrackPoint {
  lat: number;
  lon: number;
  lastSeen: number;
  onGround: boolean | null;
}

function toSegments(points: NormalizedTrackPoint[]): Array<Array<[number, number]>> {
  if (points.length < 2) {
    return [];
  }

  const segments: Array<Array<[number, number]>> = [];
  let current: Array<[number, number]> = [];
  let prevTs: number | null = null;

  for (const point of points) {
    const coords: [number, number] = [point.lat, point.lon];
    if (prevTs !== null && Math.abs(point.lastSeen - prevTs) > TRACK_SEGMENT_GAP_SECONDS) {
      if (current.length >= 2) {
        segments.push(current);
      }
      current = [coords];
    } else {
      current.push(coords);
    }
    prevTs = point.lastSeen;
  }

  if (current.length >= 2) {
    segments.push(current);
  }

  return segments;
}

function toTrackSegments(detail: FlightDetailResponse | null): ClassifiedTrackSegments {
  if (!detail?.recentTrack?.length) {
    return { current: [], historical: [] };
  }

  const valid: NormalizedTrackPoint[] = detail.recentTrack
    .filter((point) => point.lat !== null && point.lon !== null && point.lastSeen !== null)
    .map((point) => ({
      lat: point.lat as number,
      lon: point.lon as number,
      lastSeen: point.lastSeen as number,
      onGround: point.onGround ?? null
    }))
    .sort((left, right) => (left.lastSeen as number) - (right.lastSeen as number));

  if (valid.length < 2) {
    return { current: [], historical: [] };
  }

  let lastTakeoffEpoch: number | null = null;
  for (let index = 1; index < valid.length; index += 1) {
    const previous = valid[index - 1];
    const current = valid[index];
    if (previous.onGround === true && current.onGround === false) {
      lastTakeoffEpoch = current.lastSeen;
    }
  }

  const latestEpoch = valid[valid.length - 1].lastSeen;
  const currentSince = lastTakeoffEpoch ?? (latestEpoch - CURRENT_TRACK_FALLBACK_WINDOW_SECONDS);

  const historicalPoints = valid.filter((point) => point.lastSeen < currentSince);
  const currentPoints = valid.filter((point) => point.lastSeen >= currentSince);

  return {
    current: toSegments(currentPoints),
    historical: toSegments(historicalPoints)
  };
}

function hitboxDebugEnabled(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }

  const value = new URLSearchParams(window.location.search).get('debugHitbox');
  return value === '1' || value === 'true';
}

function applyMarkerHeading(marker: LeafletMarker, heading: number | null): void {
  const rotator = marker.getElement()?.querySelector('.aircraft-rotator');
  if (rotator instanceof HTMLElement) {
    rotator.style.transform = `rotate(${heading ?? 0}deg)`;
  }
}

function applyMarkerPose(marker: LeafletMarker, flight: FlightMapItem): void {
  if (isFiniteNumber(flight.lat) && isFiniteNumber(flight.lon)) {
    marker.setLatLng([flight.lat, flight.lon]);
  }
  applyMarkerHeading(marker, flight.heading);
}

function ZoomSync({ onZoomChange }: { onZoomChange: (zoom: number) => void }): null {
  useMapEvents({
    zoomend: (event) => {
      onZoomChange(event.target.getZoom());
    }
  });
  return null;
}

export default function App(): JSX.Element {
  const [flights, setFlights] = useState<FlightMapItem[]>([]);
  const [mapFlights, setMapFlights] = useState<FlightMapItem[]>([]);
  const [metrics, setMetrics] = useState<FlightsMetricsResponse | null>(null);
  const [selectedIcao24, setSelectedIcao24] = useState<string | null>(null);
  const [selectedDetail, setSelectedDetail] = useState<FlightDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [apiStatus, setApiStatus] = useState<ApiStatus>('offline');
  const [openSkyStatus, setOpenSkyStatus] = useState<OpenSkyStatus>('unknown');
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [utcClock, setUtcClock] = useState<string>(formatUtcClock(new Date()));
  const [refreshedAtIso, setRefreshedAtIso] = useState<string | null>(null);
  const [mapTheme, setMapTheme] = useState<'dark' | 'satellite'>('satellite');
  const [showCityLabels, setShowCityLabels] = useState(true);
  const [mapZoom, setMapZoom] = useState(8);
  const [effectiveBbox, setEffectiveBbox] = useState<Bbox>(IDF_BBOX);
  const [staticByIcao, setStaticByIcao] = useState<Record<string, true>>({});
  const [boostStatus, setBoostStatus] = useState<BboxBoostStatusResponse | null>(null);
  const [boostLoading, setBoostLoading] = useState(false);
  const [ingesterEnabled, setIngesterEnabled] = useState(false);
  const [ingesterKnown, setIngesterKnown] = useState(false);
  const [ingesterLoading, setIngesterLoading] = useState(false);
  const [ingesterPendingTarget, setIngesterPendingTarget] = useState<0 | 1 | null>(null);
  const [activeKpiTab, setActiveKpiTab] = useState<KpiTab>('traffic');
  const lastBatchEpochRef = useRef<number | null>(null);
  const hasMetricsRef = useRef<boolean>(false);
  const missingSelectionCyclesRef = useRef<number>(0);
  const selectedIcao24Ref = useRef<string | null>(null);
  const mapFlightsRef = useRef<FlightMapItem[]>([]);
  const markerRefsRef = useRef<Map<string, LeafletMarker>>(new Map());
  const previousSnapshotPositionsRef = useRef<Map<string, { lat: number; lon: number }>>(new Map());
  const animationFrameRef = useRef<number | null>(null);
  const effectiveBboxRef = useRef<Bbox>(IDF_BBOX);
  const refreshInFlightRef = useRef<Promise<void> | null>(null);
  const refreshQueuedRef = useRef(false);
  const refreshDataRef = useRef<() => Promise<void>>(async () => {});
  const isMountedRef = useRef(true);
  const resolveMarkerIcon = useMemo(() => createMarkerIconResolver(), []);
  const debugHitbox = useMemo(() => hitboxDebugEnabled(), []);

  const detailOpen = Boolean(selectedIcao24);

  const cancelMarkerAnimation = useCallback(() => {
    if (animationFrameRef.current !== null) {
      window.cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }
  }, []);

  const animateMarkers = useCallback(
    (targetFlights: FlightMapItem[], durationMs: number) => {
      cancelMarkerAnimation();

      const startFlights = mapFlightsRef.current;
      if (!startFlights.length || !targetFlights.length || durationMs <= 0) {
        mapFlightsRef.current = targetFlights;
        setMapFlights(targetFlights);
        return;
      }

      const startByIcao = new Map(startFlights.map((flight) => [flight.icao24, flight]));
      const animationStartedAt = performance.now();

      const tick = (now: number): void => {
        const progress = Math.min(1, (now - animationStartedAt) / durationMs);
        const nextFlights = targetFlights.map((target) => {
          const start = startByIcao.get(target.icao24);
          if (!start || !canInterpolateFlight(start, target)) {
            return target;
          }
          return interpolateFlight(start, target, progress);
        });
        mapFlightsRef.current = nextFlights;
        for (const flight of nextFlights) {
          const marker = markerRefsRef.current.get(flight.icao24);
          if (!marker) {
            continue;
          }
          applyMarkerPose(marker, flight);
        }

        if (progress < 1) {
          animationFrameRef.current = window.requestAnimationFrame(tick);
          return;
        }

        animationFrameRef.current = null;
        mapFlightsRef.current = targetFlights;
        setMapFlights(targetFlights);
      };

      animationFrameRef.current = window.requestAnimationFrame(tick);
    },
    [cancelMarkerAnimation]
  );

  const registerMarkerRef = useCallback((icao24: string, marker: LeafletMarker | null) => {
    if (!marker) {
      markerRefsRef.current.delete(icao24);
      return;
    }
    markerRefsRef.current.set(icao24, marker);
    const currentFlight = mapFlightsRef.current.find((flight) => flight.icao24 === icao24);
    if (currentFlight) {
      applyMarkerPose(marker, currentFlight);
    }
  }, []);

  const loadDetail = useCallback(async (icao24: string) => {
    try {
      if (!isMountedRef.current) {
        return;
      }
      setDetailLoading(true);
      setDetailError(null);
      const detail = await fetchFlightDetail(icao24);
      if (!isMountedRef.current) {
        return;
      }
      setSelectedDetail(detail);
    } catch (error) {
      if (!isMountedRef.current) {
        return;
      }
      const message = error instanceof Error ? error.message : 'unable to load detail';
      setDetailError(message);
    } finally {
      if (isMountedRef.current) {
        setDetailLoading(false);
      }
    }
  }, []);

  const computeStaticByIcao = useCallback((nextFlights: FlightMapItem[]): Record<string, true> => {
    // Static/grayed markers are based on displacement between two distinct OpenSky snapshots.
    const previousPositions = previousSnapshotPositionsRef.current;
    const nextPositions = new Map<string, { lat: number; lon: number }>();
    const nextStatic: Record<string, true> = {};

    for (const flight of nextFlights) {
      if (!isFiniteNumber(flight.lat) || !isFiniteNumber(flight.lon)) {
        continue;
      }

      const current = { lat: flight.lat, lon: flight.lon };
      nextPositions.set(flight.icao24, current);

      const previous = previousPositions.get(flight.icao24);
      if (!previous) {
        continue;
      }

      const movedDistanceKm = haversineKm(previous.lat, previous.lon, current.lat, current.lon);
      if (movedDistanceKm <= STATIC_POSITION_THRESHOLD_KM) {
        nextStatic[flight.icao24] = true;
      }
    }

    previousSnapshotPositionsRef.current = nextPositions;
    return nextStatic;
  }, []);

  const performRefreshCycle = useCallback(async () => {
    try {
      const boost = await fetchBboxBoostStatus();
      if (!isMountedRef.current) {
        return;
      }
      setBoostStatus(boost);
      const requestedBbox = boost.active ? boost.bbox : IDF_BBOX;
      const bboxChanged = !sameBbox(effectiveBboxRef.current, requestedBbox);
      if (bboxChanged) {
        effectiveBboxRef.current = requestedBbox;
        setEffectiveBbox(requestedBbox);
        // Reset static detection context when the observed area changes.
        previousSnapshotPositionsRef.current = new Map();
        setStaticByIcao({});
      }

      const flightsResponse = await fetchFlights(requestedBbox, 400);
      if (!isMountedRef.current) {
        return;
      }
      const normalizedFlights = normalizeFlights(flightsResponse.items);
      const batchEpoch = flightsResponse.latestOpenSkyBatchEpoch;
      const previousBatchEpoch = lastBatchEpochRef.current;
      const batchChanged = batchEpoch !== previousBatchEpoch;

      setFlights(normalizedFlights);
      // Recompute static markers only when a new batch arrives.
      // Re-evaluating on same-batch UI polls would dim most markers even without new telemetry.
      if (batchChanged) {
        setStaticByIcao(computeStaticByIcao(normalizedFlights));
      }

      const snapshotAction = resolveSnapshotUpdateAction({
        hasRenderedFlights: mapFlightsRef.current.length > 0,
        batchChanged,
        animationRunning: animationFrameRef.current !== null
      });

      if (snapshotAction === 'snap') {
        cancelMarkerAnimation();
        mapFlightsRef.current = normalizedFlights;
        setMapFlights(normalizedFlights);
      } else if (snapshotAction === 'animate') {
        animateMarkers(normalizedFlights, resolveAnimationDurationMs(batchEpoch, previousBatchEpoch));
      }

      setApiStatus('online');
      setOpenSkyStatus(computeOpenSkyStatus(normalizedFlights));
      setRefreshError(null);

      if (!hasMetricsRef.current || batchChanged || bboxChanged) {
        const metricsResponse = await fetchMetrics(requestedBbox);
        if (!isMountedRef.current) {
          return;
        }
        setMetrics(metricsResponse);
        hasMetricsRef.current = true;
      }

      if (batchChanged) {
        setRefreshedAtIso(new Date().toISOString());
        lastBatchEpochRef.current = batchEpoch;
      }

      const selectedIcao24Current = selectedIcao24Ref.current;
      if (selectedIcao24Current) {
        const stillPresent = normalizedFlights.some((flight) => flight.icao24 === selectedIcao24Current);
        if (!stillPresent) {
          missingSelectionCyclesRef.current += 1;
          if (missingSelectionCyclesRef.current >= 3) {
            setSelectedIcao24(null);
            setSelectedDetail(null);
            setDetailError(null);
          }
        } else if (batchChanged) {
          missingSelectionCyclesRef.current = 0;
          // Detail refresh is async by design: the map update path must never wait for it.
          void loadDetail(selectedIcao24Current);
        } else {
          missingSelectionCyclesRef.current = 0;
        }
      }
    } catch (error) {
      if (!isMountedRef.current) {
        return;
      }
      const message = error instanceof Error ? error.message : 'refresh failed';
      setRefreshError(message);
      setApiStatus((previous) => (previous === 'online' ? 'degraded' : 'offline'));
      setOpenSkyStatus('unknown');
    }
  }, [animateMarkers, cancelMarkerAnimation, computeStaticByIcao, loadDetail]);

  const drainQueuedRefreshes = useCallback(async () => {
    // Coalesce timer + SSE bursts; each iteration awaits I/O, so the event loop keeps progressing.
    do {
      refreshQueuedRef.current = false;
      await performRefreshCycle();
    } while (refreshQueuedRef.current && isMountedRef.current);
  }, [performRefreshCycle]);

  const refreshData = useCallback(async () => {
    if (refreshInFlightRef.current) {
      // Coalesce refresh bursts (timer + SSE): execute one extra cycle after the in-flight one.
      refreshQueuedRef.current = true;
      return refreshInFlightRef.current;
    }

    const inFlight = Promise.resolve()
      .then(drainQueuedRefreshes)
      .finally(() => {
        refreshInFlightRef.current = null;
      });

    refreshInFlightRef.current = inFlight;
    return inFlight;
  }, [drainQueuedRefreshes]);

  useEffect(() => {
    refreshDataRef.current = refreshData;
  }, [refreshData]);

  useEffect(() => {
    selectedIcao24Ref.current = selectedIcao24;
  }, [selectedIcao24]);

  useEffect(
    () => () => {
      isMountedRef.current = false;
      refreshQueuedRef.current = false;
      refreshInFlightRef.current = null;
    },
    []
  );

  const handleTriggerBoost = useCallback(async () => {
    try {
      setBoostLoading(true);
      const status = await triggerBboxBoost();
      setBoostStatus(status);
      const boostedBbox = status.active ? status.bbox : IDF_BBOX;
      effectiveBboxRef.current = boostedBbox;
      setEffectiveBbox(boostedBbox);
      await refreshData();
    } catch (error) {
      const message = error instanceof Error ? error.message : 'unable to trigger bbox boost';
      setRefreshError(message);
    } finally {
      setBoostLoading(false);
    }
  }, [refreshData]);

  const getEdgeCredentials = useCallback((interactive: boolean): EdgeCredentials | null => {
    const cached = window.sessionStorage.getItem(EDGE_AUTH_STORAGE_KEY);
    if (cached) {
      const [username, password] = cached.split(':', 2);
      if (username && password) {
        return { username, password };
      }
    }

    if (!interactive) {
      return null;
    }

    const username = window.prompt('Edge login');
    if (!username) {
      return null;
    }

    const password = window.prompt('Edge password');
    if (!password) {
      return null;
    }

    window.sessionStorage.setItem(EDGE_AUTH_STORAGE_KEY, `${username}:${password}`);
    return { username, password };
  }, []);

  const loadIngesterStatus = useCallback(async (interactive: boolean): Promise<boolean> => {
    const cachedCredentials = getEdgeCredentials(false);
    const useAuthenticatedCall = interactive || cachedCredentials !== null;
    const credentials = useAuthenticatedCall ? getEdgeCredentials(interactive) : null;

    if (useAuthenticatedCall && !credentials) {
      return false;
    }

    try {
      setIngesterLoading(true);
      const response = credentials
        ? await fetchIngesterScale(credentials.username, credentials.password)
        : await fetchIngesterScalePublic();
      setIngesterEnabled(response.replicas > 0);
      setIngesterKnown(true);
      setRefreshError(null);
      return true;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'unable to load ingester status';
      const lowerMessage = message.toLowerCase();
      const unauthorized = lowerMessage.includes('401') || lowerMessage.includes('unauthorized');
      if (useAuthenticatedCall) {
        window.sessionStorage.removeItem(EDGE_AUTH_STORAGE_KEY);
      }
      setIngesterKnown(false);
      if (!interactive && unauthorized) {
        return false;
      }
      setRefreshError(`ingester status failed: ${message}`);
      return false;
    } finally {
      setIngesterLoading(false);
    }
  }, [getEdgeCredentials]);

  const handleToggleIngester = useCallback(async (enabled: boolean) => {
    const credentials = getEdgeCredentials(true);
    if (!credentials) {
      return;
    }

    const targetReplicas: 0 | 1 = enabled ? 1 : 0;
    try {
      setIngesterLoading(true);
      setIngesterPendingTarget(targetReplicas);
      // Optimistic UI: reflect user intent immediately, then reconcile with observed replicas.
      setIngesterEnabled(targetReplicas > 0);
      setIngesterKnown(true);
      setRefreshError(null);

      const response = await scaleIngester(targetReplicas, credentials.username, credentials.password);
      let observedReplicas = response.replicas;
      setIngesterEnabled(observedReplicas > 0);

      const deadline = Date.now() + INGESTER_TOGGLE_TIMEOUT_MS;
      while ((observedReplicas > 0 ? 1 : 0) !== targetReplicas && Date.now() < deadline) {
        await waitMs(INGESTER_TOGGLE_POLL_INTERVAL_MS);
        const status = await fetchIngesterScale(credentials.username, credentials.password);
        observedReplicas = status.replicas;
        setIngesterEnabled(observedReplicas > 0);
      }

      if ((observedReplicas > 0 ? 1 : 0) !== targetReplicas) {
        throw new Error(
          `scale not converged after ${Math.round(INGESTER_TOGGLE_TIMEOUT_MS / 1000)}s `
          + `(target=${targetReplicas}, observed=${observedReplicas})`
        );
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'unable to scale ingester';
      setRefreshError(`ingester toggle failed: ${message}`);
      window.sessionStorage.removeItem(EDGE_AUTH_STORAGE_KEY);
      void loadIngesterStatus(false);
    } finally {
      setIngesterPendingTarget(null);
      setIngesterLoading(false);
    }
  }, [getEdgeCredentials, loadIngesterStatus]);

  useEffect(() => {
    const clockTimer = window.setInterval(() => {
      setUtcClock(formatUtcClock(new Date()));
    }, 1000);

    return () => window.clearInterval(clockTimer);
  }, []);

  useEffect(() => {
    void refreshDataRef.current();
    const watchdog = createRefreshWatchdog(() => {
      void refreshDataRef.current();
    }, REFRESH_INTERVAL_MS);

    const unsubscribeStream = subscribeFlightUpdates(
      (event) => {
        if (shouldRefreshFromStreamEvent(event.latestOpenSkyBatchEpoch, lastBatchEpochRef.current)) {
          void refreshDataRef.current();
          watchdog.reschedule();
        }
      },
      () => {
        setApiStatus((previous) => (previous === 'online' ? 'degraded' : previous));
      }
    );

    return () => {
      unsubscribeStream();
      watchdog.stop();
    };
  }, []);

  useEffect(() => {
    // First-page-load hardening: retry ingester status a few times without interactive auth.
    let cancelled = false;
    void (async () => {
      for (const delayMs of INGESTER_INITIAL_RETRY_DELAYS_MS) {
        if (cancelled) {
          return;
        }
        if (delayMs > 0) {
          await waitMs(delayMs);
        }
        if (cancelled) {
          return;
        }
        const ok = await loadIngesterStatus(false);
        if (ok) {
          return;
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [loadIngesterStatus]);

  useEffect(() => {
    // Keep ingester status converged over time if first load failed or backend state changes externally.
    const statusTimer = window.setInterval(() => {
      void loadIngesterStatus(false);
    }, INGESTER_STATUS_REFRESH_MS);

    return () => {
      window.clearInterval(statusTimer);
    };
  }, [loadIngesterStatus]);

  useEffect(() => {
    mapFlightsRef.current = mapFlights;
  }, [mapFlights]);

  useEffect(() => {
    for (const flight of mapFlights) {
      const marker = markerRefsRef.current.get(flight.icao24);
      if (!marker) {
        continue;
      }
      applyMarkerPose(marker, flight);
    }
  }, [mapFlights, mapZoom, selectedIcao24, staticByIcao]);

  useEffect(
    () => () => {
      cancelMarkerAnimation();
    },
    [cancelMarkerAnimation]
  );

  const trackSegments = useMemo(() => toTrackSegments(selectedDetail), [selectedDetail]);
  const selectedFlight = useMemo(() => {
    if (!selectedIcao24) {
      return null;
    }
    return (
      flights.find((flight) => flight.icao24 === selectedIcao24)
      ?? mapFlights.find((flight) => flight.icao24 === selectedIcao24)
      ?? null
    );
  }, [flights, mapFlights, selectedIcao24]);
  const activeAircraft = metrics?.activeAircraft ?? flights.length;
  const rectangleBounds = useMemo(() => toRectangleBounds(effectiveBbox), [effectiveBbox]);
  const nowEpoch = Math.floor(Date.now() / 1000);
  const boostRemainingSeconds = boostStatus?.activeUntilEpoch
    ? Math.max(0, boostStatus.activeUntilEpoch - nowEpoch)
    : 0;
  const boostCooldownSeconds = boostStatus?.cooldownUntilEpoch
    ? Math.max(0, boostStatus.cooldownUntilEpoch - nowEpoch)
    : 0;
  const boostActive = boostRemainingSeconds > 0;
  const mapTiles = useMemo(
    () =>
      mapTheme === 'satellite'
        ? {
            baseUrl: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
            labelsUrl: 'https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',
            attribution: '&copy; Esri'
          }
        : {
            baseUrl: 'https://{s}.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}{r}.png',
            labelsUrl: 'https://{s}.basemaps.cartocdn.com/dark_only_labels/{z}/{x}/{y}{r}.png',
            attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
          },
    [mapTheme]
  );

  const handleSelectFlight = useCallback(
    (flight: FlightMapItem) => {
      missingSelectionCyclesRef.current = 0;
      setSelectedIcao24(flight.icao24);
      // Keep selection responsive and map refresh independent from detail API latency.
      void loadDetail(flight.icao24);
    },
    [loadDetail]
  );

  const handleCloseDetail = useCallback(() => {
    missingSelectionCyclesRef.current = 0;
    setSelectedIcao24(null);
    setSelectedDetail(null);
    setDetailError(null);
  }, []);

  const markerElements = useMemo(
    () =>
      mapFlights.map((flight) => {
        const selected = selectedIcao24 === flight.icao24;
        const isStatic = Boolean(staticByIcao[flight.icao24]);
        return (
          <Marker
            key={flight.icao24}
            ref={(marker) => registerMarkerRef(flight.icao24, marker)}
            position={[flight.lat as number, flight.lon as number]}
            icon={resolveMarkerIcon({
              flight,
              selected,
              zoom: mapZoom,
              isStatic,
              debugHitbox
            })}
            eventHandlers={{
              mousedown: (event) => {
                const mouseEvent = event.originalEvent as MouseEvent | undefined;
                if (mouseEvent && mouseEvent.button !== 0) {
                  return;
                }
                void handleSelectFlight(flight);
              }
            }}
          >
            <Popup>
              <div className="popup-content">
                <strong>{flight.callsign || flight.icao24}</strong>
                <div>icao24: {flight.icao24}</div>
                <div>altitude: {flight.altitude ?? 'n/a'} m</div>
                <div>speed: {formatSpeedKmh(flight.speed)}</div>
              </div>
            </Popup>
          </Marker>
        );
      }),
    [debugHitbox, handleSelectFlight, mapFlights, mapZoom, registerMarkerRef, resolveMarkerIcon, selectedIcao24, staticByIcao]
  );

  return (
    <main className="main-layout">
      <div className="background-grid" aria-hidden="true" />

      <Header
        apiStatus={apiStatus}
        openSkyStatus={openSkyStatus}
        utcClock={utcClock}
        refreshedAt={formatRefreshedAt(refreshedAtIso)}
        activeAircraft={activeAircraft}
        mapTheme={mapTheme}
        onThemeChange={setMapTheme}
        showCityLabels={showCityLabels}
        onToggleCityLabels={() => setShowCityLabels((previous) => !previous)}
        boostActive={boostActive}
        boostRemainingSeconds={boostRemainingSeconds}
        boostCooldownSeconds={boostCooldownSeconds}
        boostLoading={boostLoading}
        onTriggerBoost={() => void handleTriggerBoost()}
        ingesterEnabled={ingesterEnabled}
        ingesterKnown={ingesterKnown}
        ingesterLoading={ingesterLoading}
        ingesterPendingTarget={ingesterPendingTarget}
        onToggleIngester={(enabled) => void handleToggleIngester(enabled)}
      />

      <section className="map-shell glass-panel">
        {refreshError && <div className="refresh-error">{refreshError}</div>}
        {mapTheme === 'satellite' && <div className="map-dark-overlay" />}

        <MapContainer center={MAP_CENTER} zoom={8} maxBounds={MAX_BOUNDS} maxBoundsViscosity={1.0} zoomControl={false}>
          <ZoomSync onZoomChange={setMapZoom} />
          <TileLayer url={mapTiles.baseUrl} attribution={mapTiles.attribution} />
          {showCityLabels && <TileLayer url={mapTiles.labelsUrl} attribution={mapTiles.attribution} opacity={0.9} />}

          <Rectangle pathOptions={{ color: '#00f5ff', dashArray: '6 4', weight: 2, fillOpacity: 0.04 }} bounds={rectangleBounds} />

          {trackSegments.historical.map((segment, index) => (
            <Polyline
              key={`track-historical-${index}`}
              positions={segment}
              pathOptions={{ color: '#d5dee8', weight: 2, opacity: 0.65, dashArray: '4 4' }}
            />
          ))}
          {trackSegments.current.map((segment, index) => (
            <Polyline
              key={`track-current-${index}`}
              positions={segment}
              pathOptions={{ color: '#00f5ff', weight: 3, opacity: 0.92 }}
            />
          ))}

          {markerElements}
        </MapContainer>
        <MapLegend />
      </section>

      <DetailPanel
        detail={selectedDetail}
        fleetType={selectedFlight?.fleetType ?? null}
        open={detailOpen}
        loading={detailLoading}
        error={detailError}
        onClose={handleCloseDetail}
      />

      <section className="kpi-tabs glass-panel" aria-label="KPI categories">
        <button
          type="button"
          className={`kpi-tab-button${activeKpiTab === 'traffic' ? ' is-active' : ''}`}
          aria-pressed={activeKpiTab === 'traffic'}
          aria-controls="kpi-panel-traffic"
          onClick={() => setActiveKpiTab('traffic')}
        >
          Traffic density
        </button>
        <button
          type="button"
          className={`kpi-tab-button${activeKpiTab === 'defense' ? ' is-active' : ''}`}
          aria-pressed={activeKpiTab === 'defense'}
          aria-controls="kpi-panel-defense"
          onClick={() => setActiveKpiTab('defense')}
        >
          Military share
        </button>
        <button
          type="button"
          className={`kpi-tab-button${activeKpiTab === 'fleet' ? ' is-active' : ''}`}
          aria-pressed={activeKpiTab === 'fleet'}
          aria-controls="kpi-panel-fleet"
          onClick={() => setActiveKpiTab('fleet')}
        >
          Aircraft types
        </button>
      </section>

      <KpiStrip flights={flights} metrics={metrics} activeTab={activeKpiTab} />
    </main>
  );
}
