import { API_FLIGHTS_BASE } from './constants';
import type {
  Bbox,
  FlightDetailResponse,
  FlightListResponse,
  FlightsMetricsResponse
} from './types';

function toBboxQuery(bbox: Bbox): string {
  return `${bbox.minLon},${bbox.minLat},${bbox.maxLon},${bbox.maxLat}`;
}

async function apiGet<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    headers: {
      Accept: 'application/json'
    }
  });

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const payload = await response.json();
      if (payload?.message) {
        message = payload.message;
      }
    } catch {
      // Keep default HTTP status text when body is not JSON.
    }
    throw new Error(message);
  }

  return (await response.json()) as T;
}

export async function fetchFlights(bbox: Bbox, limit = 400): Promise<FlightListResponse> {
  const params = new URLSearchParams({
    bbox: toBboxQuery(bbox),
    limit: String(limit),
    sort: 'lastSeen',
    order: 'desc'
  });

  return apiGet<FlightListResponse>(`${API_FLIGHTS_BASE}?${params.toString()}`);
}

export async function fetchFlightDetail(icao24: string): Promise<FlightDetailResponse> {
  return apiGet<FlightDetailResponse>(`${API_FLIGHTS_BASE}/${icao24}?include=track,enrichment`);
}

export async function fetchMetrics(bbox: Bbox): Promise<FlightsMetricsResponse> {
  const params = new URLSearchParams({
    bbox: toBboxQuery(bbox),
    window: '6h'
  });

  return apiGet<FlightsMetricsResponse>(`${API_FLIGHTS_BASE}/metrics?${params.toString()}`);
}

export interface FlightStreamEvent {
  latestOpenSkyBatchEpoch: number | null;
  timestamp: string;
}

function parseStreamEvent(raw: string): FlightStreamEvent | null {
  try {
    const payload = JSON.parse(raw) as Partial<FlightStreamEvent>;
    return {
      latestOpenSkyBatchEpoch:
        typeof payload.latestOpenSkyBatchEpoch === 'number' ? payload.latestOpenSkyBatchEpoch : null,
      timestamp: typeof payload.timestamp === 'string' ? payload.timestamp : new Date().toISOString()
    };
  } catch {
    return null;
  }
}

export function subscribeFlightUpdates(
  onUpdate: (event: FlightStreamEvent) => void,
  onError?: () => void
): () => void {
  const stream = new EventSource(`${API_FLIGHTS_BASE}/stream`);

  const handleEvent = (event: MessageEvent): void => {
    const parsed = parseStreamEvent(event.data);
    if (parsed) {
      onUpdate(parsed);
    }
  };

  stream.addEventListener('batch-update', handleEvent as EventListener);
  stream.addEventListener('connected', handleEvent as EventListener);
  stream.onerror = () => onError?.();

  return () => {
    stream.removeEventListener('batch-update', handleEvent as EventListener);
    stream.removeEventListener('connected', handleEvent as EventListener);
    stream.close();
  };
}
