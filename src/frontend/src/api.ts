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
