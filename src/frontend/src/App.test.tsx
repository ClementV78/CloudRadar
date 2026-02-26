import { forwardRef, useEffect, type ReactNode } from 'react';
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

import App from './App';
import * as api from './api';
import type {
  Bbox,
  BboxBoostStatusResponse,
  FlightDetailResponse,
  FlightListResponse,
  FlightMapItem,
  FlightsMetricsResponse,
  IngesterScaleResponse
} from './types';

vi.mock('./api', () => ({
  fetchBboxBoostStatus: vi.fn(),
  fetchFlightDetail: vi.fn(),
  fetchFlights: vi.fn(),
  fetchIngesterScale: vi.fn(),
  fetchIngesterScalePublic: vi.fn(),
  fetchMetrics: vi.fn(),
  scaleIngester: vi.fn(),
  subscribeFlightUpdates: vi.fn(),
  triggerBboxBoost: vi.fn()
}));

vi.mock('leaflet', () => ({
  default: {
    divIcon: vi.fn(() => ({}))
  },
  divIcon: vi.fn(() => ({}))
}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children?: ReactNode }) => <div data-testid="leaflet-map">{children}</div>,
  Marker: forwardRef(({ children }: { children?: ReactNode }, ref) => {
    useEffect(() => {
      const markerMock = {
        setLatLng: () => {},
        getElement: () => document.createElement('div')
      };

      if (typeof ref === 'function') {
        ref(markerMock as never);
        return () => ref(null);
      }
      if (ref) {
        (ref as { current: unknown }).current = markerMock;
        return () => {
          (ref as { current: unknown }).current = null;
        };
      }
      return undefined;
    }, [ref]);

    return <div data-testid="map-marker">{children}</div>;
  }),
  Polyline: () => <div data-testid="map-polyline" />,
  Popup: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  Rectangle: () => <div data-testid="map-bbox" />,
  TileLayer: () => null,
  useMapEvents: () => ({})
}));

const TEST_BBOX: Bbox = {
  minLon: 1,
  minLat: 1,
  maxLon: 2,
  maxLat: 2
};

const TEST_TIMESTAMP = '2026-02-24T00:00:00Z';

const DEFAULT_BOOST_STATUS: BboxBoostStatusResponse = {
  active: false,
  factor: 2,
  bbox: TEST_BBOX,
  activeUntilEpoch: null,
  cooldownUntilEpoch: null,
  serverEpoch: 1_700_000_000
};

const DEFAULT_METRICS: FlightsMetricsResponse = {
  activeAircraft: 0,
  trafficDensityPer10kKm2: 0,
  militarySharePercent: 0,
  defenseActivityScore: 0,
  fleetBreakdown: [],
  aircraftSizes: [],
  aircraftTypes: [],
  activitySeries: [],
  activityBucketSeconds: 600,
  activityWindowSeconds: 21_600,
  openSkyCreditsPerRequest24h: null,
  timestamp: TEST_TIMESTAMP
};

const DEFAULT_SCALE: IngesterScaleResponse = {
  status: 'ok',
  deployment: 'ingester',
  replicas: 1,
  available: 1,
  updated: 1,
  timestamp: TEST_TIMESTAMP
};

const DEFAULT_DETAIL: FlightDetailResponse = {
  icao24: 'abc123',
  callsign: 'AFR123',
  registration: null,
  manufacturer: null,
  model: null,
  typecode: null,
  category: null,
  lat: null,
  lon: null,
  heading: null,
  altitude: null,
  groundSpeed: null,
  verticalRate: null,
  lastSeen: null,
  onGround: null,
  country: null,
  militaryHint: null,
  yearBuilt: null,
  ownerOperator: null,
  photo: null,
  recentTrack: [],
  timestamp: TEST_TIMESTAMP
};

function buildFlightsResponse(items: FlightMapItem[]): FlightListResponse {
  return {
    items,
    count: items.length,
    totalMatched: items.length,
    limit: 400,
    bbox: TEST_BBOX,
    latestOpenSkyBatchEpoch: 1_700_000_000,
    timestamp: TEST_TIMESTAMP
  };
}

function flightFixture(overrides: Partial<FlightMapItem> = {}): FlightMapItem {
  return {
    icao24: 'abc123',
    callsign: 'AFR123',
    lat: 48.8566,
    lon: 2.3522,
    heading: 180,
    lastSeen: 1_700_000_000,
    speed: 200,
    altitude: 10_000,
    militaryHint: false,
    airframeType: 'airplane',
    fleetType: 'commercial',
    aircraftSize: 'medium',
    ...overrides
  };
}

beforeEach(() => {
  vi.clearAllMocks();

  vi.mocked(api.fetchBboxBoostStatus).mockResolvedValue(DEFAULT_BOOST_STATUS);
  vi.mocked(api.fetchMetrics).mockResolvedValue(DEFAULT_METRICS);
  vi.mocked(api.fetchIngesterScalePublic).mockResolvedValue(DEFAULT_SCALE);
  vi.mocked(api.fetchIngesterScale).mockResolvedValue(DEFAULT_SCALE);
  vi.mocked(api.scaleIngester).mockResolvedValue(DEFAULT_SCALE);
  vi.mocked(api.triggerBboxBoost).mockResolvedValue(DEFAULT_BOOST_STATUS);
  vi.mocked(api.fetchFlightDetail).mockResolvedValue(DEFAULT_DETAIL);
  vi.mocked(api.subscribeFlightUpdates).mockImplementation(() => () => {});
});

afterEach(() => {
  vi.useRealTimers();
});

describe('App UI smoke', () => {
  it('renders app shell and map container with no flights', async () => {
    vi.mocked(api.fetchFlights).mockResolvedValue(buildFlightsResponse([]));

    render(<App />);

    expect(await screen.findByText('CloudRadar')).toBeInTheDocument();
    expect(screen.getByTestId('leaflet-map')).toBeInTheDocument();

    await waitFor(() => {
      expect(api.fetchFlights).toHaveBeenCalledTimes(1);
    });

    expect(screen.queryAllByTestId('map-marker')).toHaveLength(0);
  });

  it('renders one map marker when flights are returned', async () => {
    vi.mocked(api.fetchFlights).mockResolvedValue(buildFlightsResponse([flightFixture()]));

    render(<App />);

    await waitFor(() => {
      expect(screen.getAllByTestId('map-marker')).toHaveLength(1);
    });

    expect(screen.getByText('AFR123')).toBeInTheDocument();
  });

  it('switches KPI tab selection when category is clicked', async () => {
    vi.mocked(api.fetchFlights).mockResolvedValue(buildFlightsResponse([]));

    render(<App />);

    const trafficButton = await screen.findByRole('button', { name: 'Traffic density' });
    const defenseButton = screen.getByRole('button', { name: 'Military share' });

    expect(trafficButton).toHaveAttribute('aria-pressed', 'true');
    expect(defenseButton).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(defenseButton);

    expect(defenseButton).toHaveAttribute('aria-pressed', 'true');
    expect(trafficButton).toHaveAttribute('aria-pressed', 'false');
  });
});
