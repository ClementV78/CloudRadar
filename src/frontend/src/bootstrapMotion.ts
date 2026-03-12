import type { FlightMapItem } from './types';

export interface BootstrapMotionPlan {
  startFlights: FlightMapItem[];
  durationMs: number;
}

function isFiniteNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function canUsePreviousSnapshot(flight: FlightMapItem): boolean {
  if (
    !isFiniteNumber(flight.lat) || !isFiniteNumber(flight.lon)
    || !isFiniteNumber(flight.prevLat) || !isFiniteNumber(flight.prevLon)
  ) {
    return false;
  }

  if (isFiniteNumber(flight.prevLastSeen) && isFiniteNumber(flight.lastSeen) && flight.lastSeen < flight.prevLastSeen) {
    return false;
  }

  return true;
}

function toStartFlight(flight: FlightMapItem): FlightMapItem {
  if (!canUsePreviousSnapshot(flight)) {
    return flight;
  }

  return {
    ...flight,
    lat: flight.prevLat ?? flight.lat,
    lon: flight.prevLon ?? flight.lon,
    heading: flight.prevHeading ?? flight.heading,
    speed: flight.prevSpeed ?? flight.speed,
    altitude: flight.prevAltitude ?? flight.altitude,
    lastSeen: flight.prevLastSeen ?? flight.lastSeen
  };
}

function median(values: number[]): number | null {
  if (!values.length) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  if (sorted.length % 2 === 0) {
    return (sorted[middle - 1] + sorted[middle]) / 2;
  }
  return sorted[middle];
}

export function buildBootstrapMotionPlan(
  targetFlights: FlightMapItem[],
  fallbackDurationMs: number,
  minDurationMs: number,
  maxDurationMs: number
): BootstrapMotionPlan | null {
  const candidates = targetFlights.filter(canUsePreviousSnapshot);
  if (!candidates.length) {
    return null;
  }

  const deltaSeconds = candidates
    .map((flight) => {
      if (!isFiniteNumber(flight.prevLastSeen) || !isFiniteNumber(flight.lastSeen)) {
        return null;
      }
      const delta = flight.lastSeen - flight.prevLastSeen;
      return delta > 0 ? delta : null;
    })
    .filter((value): value is number => isFiniteNumber(value));

  const measuredDurationMs = median(deltaSeconds);
  const durationMs = clamp(
    measuredDurationMs !== null ? measuredDurationMs * 1000 : fallbackDurationMs,
    minDurationMs,
    maxDurationMs
  );

  return {
    startFlights: targetFlights.map(toStartFlight),
    durationMs
  };
}
