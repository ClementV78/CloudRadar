import { useCallback, useEffect, useMemo, useState } from 'react';
import { MapContainer, Marker, Polyline, Popup, Rectangle, TileLayer } from 'react-leaflet';
import L, { type DivIcon } from 'leaflet';

import { fetchFlightDetail, fetchFlights, fetchMetrics } from './api';
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

function normalizeFlights(items: FlightMapItem[]): FlightMapItem[] {
  return items.filter((item) => item.lat !== null && item.lon !== null);
}

function toTrackPolyline(detail: FlightDetailResponse | null): Array<[number, number]> {
  if (!detail?.recentTrack?.length) {
    return [];
  }

  return detail.recentTrack
    .filter((point) => point.lat !== null && point.lon !== null)
    .map((point) => [point.lat as number, point.lon as number]);
}

function markerIcon(flight: FlightMapItem, selected: boolean, detail: FlightDetailResponse | null): DivIcon {
  const heading = flight.heading ?? 0;
  const militaryHint = selected ? detail?.militaryHint : null;
  const color = militaryHint ? '#ff4b4b' : '#00f5ff';
  const stroke = selected ? '#ffffff' : '#041018';
  const pulseClass = selected ? 'marker-pulse' : '';

  return L.divIcon({
    className: `aircraft-div-icon ${pulseClass}`,
    iconSize: [28, 28],
    iconAnchor: [14, 14],
    html: `
      <div class="aircraft-marker" style="transform: rotate(${heading}deg)">
        <svg viewBox="0 0 40 40" width="28" height="28" aria-hidden="true">
          <path d="M20 2 L26 15 L37 18 L26 21 L20 38 L14 21 L3 18 L14 15 Z" fill="${color}" stroke="${stroke}" stroke-width="2"/>
          <circle cx="20" cy="20" r="2" fill="#0b0e14" />
        </svg>
      </div>
    `
  });
}

export default function App(): JSX.Element {
  const [flights, setFlights] = useState<FlightMapItem[]>([]);
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

  const detailOpen = Boolean(selectedIcao24);

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
      const [flightsResponse, metricsResponse] = await Promise.all([
        fetchFlights(IDF_BBOX, 400),
        fetchMetrics(IDF_BBOX)
      ]);

      const normalizedFlights = normalizeFlights(flightsResponse.items);
      setFlights(normalizedFlights);
      setMetrics(metricsResponse);
      setApiStatus('online');
      setOpenSkyStatus(computeOpenSkyStatus(normalizedFlights));
      setRefreshError(null);
      setRefreshedAtIso(new Date().toISOString());

      if (selectedIcao24) {
        const stillPresent = normalizedFlights.some((flight) => flight.icao24 === selectedIcao24);
        if (!stillPresent) {
          setSelectedIcao24(null);
          setSelectedDetail(null);
          setDetailError(null);
        } else {
          await loadDetail(selectedIcao24);
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'refresh failed';
      setRefreshError(message);
      setApiStatus((previous) => (previous === 'online' ? 'degraded' : 'offline'));
      setOpenSkyStatus('unknown');
    }
  }, [loadDetail, selectedIcao24]);

  useEffect(() => {
    const clockTimer = window.setInterval(() => {
      setUtcClock(formatUtcClock(new Date()));
    }, 1000);

    return () => window.clearInterval(clockTimer);
  }, []);

  useEffect(() => {
    void refreshData();
    const refreshTimer = window.setInterval(() => {
      void refreshData();
    }, REFRESH_INTERVAL_MS);

    return () => window.clearInterval(refreshTimer);
  }, [refreshData]);

  const trackPolyline = useMemo(() => toTrackPolyline(selectedDetail), [selectedDetail]);
  const activeAircraft = metrics?.activeAircraft ?? flights.length;
  const mapTile = useMemo(
    () =>
      mapTheme === 'satellite'
        ? {
            url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
            attribution: '&copy; Esri'
          }
        : {
            url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
            attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
          },
    [mapTheme]
  );

  const handleSelectFlight = useCallback(
    async (flight: FlightMapItem) => {
      setSelectedIcao24(flight.icao24);
      await loadDetail(flight.icao24);
    },
    [loadDetail]
  );

  const handleCloseDetail = useCallback(() => {
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
      />

      <section className="map-shell glass-panel">
        {refreshError && <div className="refresh-error">{refreshError}</div>}
        {mapTheme === 'satellite' && <div className="map-dark-overlay" />}

        <MapContainer center={MAP_CENTER} zoom={8} maxBounds={MAX_BOUNDS} maxBoundsViscosity={1.0} zoomControl={false}>
          <TileLayer url={mapTile.url} attribution={mapTile.attribution} />

          <Rectangle pathOptions={{ color: '#00f5ff', dashArray: '6 4', weight: 2, fillOpacity: 0.04 }} bounds={IDF_RECTANGLE} />

          {trackPolyline.length >= 2 && (
            <Polyline positions={trackPolyline} pathOptions={{ color: '#00f5ff', weight: 3, opacity: 0.9 }} />
          )}

          {flights.map((flight) => {
            const selected = selectedIcao24 === flight.icao24;
            return (
              <Marker
                key={flight.icao24}
                position={[flight.lat as number, flight.lon as number]}
                icon={markerIcon(flight, selected, selectedDetail)}
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
