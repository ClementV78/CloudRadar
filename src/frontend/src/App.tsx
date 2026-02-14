import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { MapContainer, Marker, Polyline, Popup, Rectangle, TileLayer, useMapEvents } from 'react-leaflet';
import L, { type DivIcon } from 'leaflet';

import { fetchFlightDetail, fetchFlights, fetchMetrics, subscribeFlightUpdates } from './api';
import { IDF_BBOX, MAP_MAX_BOUNDS, REFRESH_INTERVAL_MS, STALE_AFTER_SECONDS } from './constants';
import { DetailPanel } from './components/DetailPanel';
import { Header } from './components/Header';
import { KpiStrip } from './components/KpiStrip';
import type { ApiStatus, FlightDetailResponse, FlightMapItem, FlightsMetricsResponse, OpenSkyStatus } from './types';

const MAP_CENTER: [number, number] = [
  (IDF_BBOX.minLat + IDF_BBOX.maxLat) / 2,
  (IDF_BBOX.minLon + IDF_BBOX.maxLon) / 2
];

const MAX_BOUNDS: [[number, number], [number, number]] = [
  [MAP_MAX_BOUNDS.minLat, MAP_MAX_BOUNDS.minLon],
  [MAP_MAX_BOUNDS.maxLat, MAP_MAX_BOUNDS.maxLon]
];

const IDF_RECTANGLE: [[number, number], [number, number]] = [
  [IDF_BBOX.minLat, IDF_BBOX.minLon],
  [IDF_BBOX.maxLat, IDF_BBOX.maxLon]
];

const TRACK_SEGMENT_GAP_SECONDS = 15 * 60;
const MIN_INTERPOLATION_DURATION_MS = 1_000;
const MAX_INTERPOLATION_DURATION_MS = 20_000;
const MAX_INTERPOLATION_LAST_SEEN_GAP_SECONDS = 180;
const MAX_INTERPOLATION_DISTANCE_KM = 200;

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

function toTrackSegments(detail: FlightDetailResponse | null): Array<Array<[number, number]>> {
  if (!detail?.recentTrack?.length) {
    return [];
  }

  const valid = detail.recentTrack
    .filter((point) => point.lat !== null && point.lon !== null && point.lastSeen !== null)
    .sort((left, right) => (left.lastSeen as number) - (right.lastSeen as number));

  if (valid.length < 2) {
    return [];
  }

  const segments: Array<Array<[number, number]>> = [];
  let current: Array<[number, number]> = [];
  let prevTs: number | null = null;

  for (const point of valid) {
    const ts = point.lastSeen as number;
    const coords: [number, number] = [point.lat as number, point.lon as number];

    if (prevTs !== null && Math.abs(ts - prevTs) > TRACK_SEGMENT_GAP_SECONDS) {
      if (current.length >= 2) {
        segments.push(current);
      }
      current = [coords];
    } else {
      current.push(coords);
    }

    prevTs = ts;
  }

  if (current.length >= 2) {
    segments.push(current);
  }

  return segments;
}

function markerBaseSize(size: FlightMapItem['aircraftSize']): number {
  switch (size) {
    case 'small':
      return 14;
    case 'medium':
      return 18;
    case 'large':
      return 23;
    case 'heavy':
      return 29;
    case 'unknown':
    default:
      return 16;
  }
}

function markerColor(flight: FlightMapItem): string {
  if (flight.militaryHint === true || flight.fleetType === 'military') {
    return '#ff4b4b';
  }

  switch (flight.fleetType) {
    case 'commercial':
      return '#45e7ff';
    case 'private':
      return '#f8e16c';
    case 'unknown':
    default:
      return '#a7bbd6';
  }
}

function markerStroke(flight: FlightMapItem): string {
  if (flight.airframeType === 'helicopter') {
    return '#6dffb6';
  }
  switch (flight.aircraftSize) {
    case 'heavy':
      return '#ffaf61';
    case 'large':
      return '#56b5ff';
    case 'small':
      return '#d2ff7e';
    case 'medium':
    case 'unknown':
    default:
      return '#0a1722';
  }
}

function markerGlyph(flight: FlightMapItem, size: number, color: string, stroke: string, selected: boolean): string {
  const strokeWidth = selected ? 2.8 : 2;
  if (flight.airframeType === 'helicopter') {
    return `
      <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true">
        <line x1="6" y1="9" x2="34" y2="9" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="round"/>
        <line x1="20" y1="9" x2="20" y2="14" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="round"/>
        <rect x="15.5" y="14" width="9" height="11" rx="4" fill="${color}" stroke="${stroke}" stroke-width="${strokeWidth}"/>
        <line x1="24.5" y1="20" x2="33" y2="23" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="round"/>
        <line x1="33" y1="23" x2="36" y2="23" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="round"/>
        <line x1="14" y1="28" x2="26" y2="28" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="round"/>
      </svg>
    `;
  }

  return `
    <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true">
      <path d="M19 3 H21 L23 14 L34 18 V22 L23 20 L21 37 H19 L17 20 L6 22 V18 L17 14 Z" fill="${color}" stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linejoin="round"/>
      <circle cx="20" cy="16" r="1.8" fill="${stroke}" />
    </svg>
  `;
}

function markerIcon(flight: FlightMapItem, selected: boolean, zoom: number): DivIcon {
  const heading = flight.heading ?? 0;
  const baseSize = markerBaseSize(flight.aircraftSize);
  const scale = Math.min(2.3, Math.max(0.9, 1 + (zoom - 8) * 0.18));
  const size = Math.round(baseSize * scale);
  const color = markerColor(flight);
  const stroke = selected ? '#ffffff' : markerStroke(flight);
  const pulseClass = selected ? 'marker-pulse' : '';
  const glyph = markerGlyph(flight, size, color, stroke, selected);

  return L.divIcon({
    className: `aircraft-div-icon ${pulseClass}`,
    iconSize: [size, size],
    iconAnchor: [Math.round(size / 2), Math.round(size / 2)],
    html: `
      <div class="aircraft-marker" style="transform: rotate(${heading}deg)">
        ${glyph}
      </div>
    `
  });
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
  const lastBatchEpochRef = useRef<number | null>(null);
  const hasMetricsRef = useRef<boolean>(false);
  const missingSelectionCyclesRef = useRef<number>(0);
  const mapFlightsRef = useRef<FlightMapItem[]>([]);
  const animationFrameRef = useRef<number | null>(null);

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

        setMapFlights(nextFlights);

        if (progress < 1) {
          animationFrameRef.current = window.requestAnimationFrame(tick);
          return;
        }

        animationFrameRef.current = null;
        setMapFlights(targetFlights);
      };

      animationFrameRef.current = window.requestAnimationFrame(tick);
    },
    [cancelMarkerAnimation]
  );

  const loadDetail = useCallback(async (icao24: string) => {
    try {
      setDetailLoading(true);
      setDetailError(null);
      const detail = await fetchFlightDetail(icao24);
      setSelectedDetail(detail);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'unable to load detail';
      setDetailError(message);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const refreshData = useCallback(async () => {
    try {
      const flightsResponse = await fetchFlights(IDF_BBOX, 400);
      const normalizedFlights = normalizeFlights(flightsResponse.items);
      const batchEpoch = flightsResponse.latestOpenSkyBatchEpoch;
      const previousBatchEpoch = lastBatchEpochRef.current;
      const batchChanged = batchEpoch !== previousBatchEpoch;

      setFlights(normalizedFlights);

      if (!mapFlightsRef.current.length) {
        cancelMarkerAnimation();
        setMapFlights(normalizedFlights);
      } else if (batchChanged) {
        animateMarkers(normalizedFlights, resolveAnimationDurationMs(batchEpoch, previousBatchEpoch));
      } else {
        cancelMarkerAnimation();
        setMapFlights(normalizedFlights);
      }

      setApiStatus('online');
      setOpenSkyStatus(computeOpenSkyStatus(normalizedFlights));
      setRefreshError(null);

      if (!hasMetricsRef.current || batchChanged) {
        const metricsResponse = await fetchMetrics(IDF_BBOX);
        setMetrics(metricsResponse);
        hasMetricsRef.current = true;
      }

      if (batchChanged) {
        setRefreshedAtIso(new Date().toISOString());
        lastBatchEpochRef.current = batchEpoch;
      }

      if (selectedIcao24) {
        const stillPresent = normalizedFlights.some((flight) => flight.icao24 === selectedIcao24);
        if (!stillPresent) {
          missingSelectionCyclesRef.current += 1;
          if (missingSelectionCyclesRef.current >= 3) {
            setSelectedIcao24(null);
            setSelectedDetail(null);
            setDetailError(null);
          }
        } else if (batchChanged) {
          missingSelectionCyclesRef.current = 0;
          await loadDetail(selectedIcao24);
        } else {
          missingSelectionCyclesRef.current = 0;
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'refresh failed';
      setRefreshError(message);
      setApiStatus((previous) => (previous === 'online' ? 'degraded' : 'offline'));
      setOpenSkyStatus('unknown');
    }
  }, [animateMarkers, cancelMarkerAnimation, loadDetail, selectedIcao24]);

  useEffect(() => {
    const clockTimer = window.setInterval(() => {
      setUtcClock(formatUtcClock(new Date()));
    }, 1000);

    return () => window.clearInterval(clockTimer);
  }, []);

  useEffect(() => {
    void refreshData();
    const unsubscribeStream = subscribeFlightUpdates(
      (event) => {
        if (
          event.latestOpenSkyBatchEpoch === null
          || event.latestOpenSkyBatchEpoch !== lastBatchEpochRef.current
        ) {
          void refreshData();
        }
      },
      () => {
        setApiStatus((previous) => (previous === 'online' ? 'degraded' : previous));
      }
    );

    const refreshTimer = window.setInterval(() => {
      void refreshData();
    }, REFRESH_INTERVAL_MS);

    return () => {
      unsubscribeStream();
      window.clearInterval(refreshTimer);
    };
  }, [refreshData]);

  useEffect(() => {
    mapFlightsRef.current = mapFlights;
  }, [mapFlights]);

  useEffect(
    () => () => {
      cancelMarkerAnimation();
    },
    [cancelMarkerAnimation]
  );

  const trackSegments = useMemo(() => toTrackSegments(selectedDetail), [selectedDetail]);
  const activeAircraft = metrics?.activeAircraft ?? flights.length;
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
    async (flight: FlightMapItem) => {
      missingSelectionCyclesRef.current = 0;
      setSelectedIcao24(flight.icao24);
      await loadDetail(flight.icao24);
    },
    [loadDetail]
  );

  const handleCloseDetail = useCallback(() => {
    missingSelectionCyclesRef.current = 0;
    setSelectedIcao24(null);
    setSelectedDetail(null);
    setDetailError(null);
  }, []);

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
      />

      <section className="map-shell glass-panel">
        {refreshError && <div className="refresh-error">{refreshError}</div>}
        {mapTheme === 'satellite' && <div className="map-dark-overlay" />}

        <MapContainer center={MAP_CENTER} zoom={8} maxBounds={MAX_BOUNDS} maxBoundsViscosity={1.0} zoomControl={false}>
          <ZoomSync onZoomChange={setMapZoom} />
          <TileLayer url={mapTiles.baseUrl} attribution={mapTiles.attribution} />
          {showCityLabels && <TileLayer url={mapTiles.labelsUrl} attribution={mapTiles.attribution} opacity={0.9} />}

          <Rectangle pathOptions={{ color: '#00f5ff', dashArray: '6 4', weight: 2, fillOpacity: 0.04 }} bounds={IDF_RECTANGLE} />

          {trackSegments.map((segment, index) => (
            <Polyline key={`track-${index}`} positions={segment} pathOptions={{ color: '#00f5ff', weight: 3, opacity: 0.9 }} />
          ))}

          {mapFlights.map((flight) => {
            const selected = selectedIcao24 === flight.icao24;
            return (
              <Marker
                key={flight.icao24}
                position={[flight.lat as number, flight.lon as number]}
                icon={markerIcon(flight, selected, mapZoom)}
                eventHandlers={{
                  click: () => {
                    void handleSelectFlight(flight);
                  }
                }}
              >
                <Popup>
                  <div className="popup-content">
                    <strong>{flight.callsign || flight.icao24}</strong>
                    <div>icao24: {flight.icao24}</div>
                    <div>altitude: {flight.altitude ?? 'n/a'} m</div>
                    <div>speed: {flight.speed ?? 'n/a'} kt</div>
                  </div>
                </Popup>
              </Marker>
            );
          })}
        </MapContainer>
      </section>

      <DetailPanel
        detail={selectedDetail}
        open={detailOpen}
        loading={detailLoading}
        error={detailError}
        onClose={handleCloseDetail}
      />

      <section className="kpi-tabs glass-panel">
        <span className="active">Traffic density</span>
        <span>Military share</span>
        <span>Aircraft types</span>
      </section>

      <KpiStrip flights={flights} metrics={metrics} />
    </main>
  );
}
