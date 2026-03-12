import { describe, expect, it } from 'vitest';

import { buildBootstrapMotionPlan } from './bootstrapMotion';
import type { FlightMapItem } from './types';

function flightFixture(overrides: Partial<FlightMapItem> = {}): FlightMapItem {
  return {
    icao24: 'abc123',
    callsign: 'AFR123',
    lat: 48.8566,
    lon: 2.3522,
    heading: 180,
    lastSeen: 1_700_000_010,
    speed: 220,
    altitude: 10_000,
    militaryHint: false,
    airframeType: 'airplane',
    fleetType: 'commercial',
    aircraftSize: 'medium',
    ...overrides
  };
}

describe('bootstrapMotion', () => {
  it('returns null when no previous snapshot is available', () => {
    const plan = buildBootstrapMotionPlan([flightFixture()], 10_000, 1_000, 20_000);
    expect(plan).toBeNull();
  });

  it('builds start snapshot from prev fields and derives duration from lastSeen delta', () => {
    const plan = buildBootstrapMotionPlan(
      [
        flightFixture({
          prevLat: 48.8560,
          prevLon: 2.3510,
          prevHeading: 175,
          prevSpeed: 210,
          prevAltitude: 9_900,
          prevLastSeen: 1_700_000_000
        })
      ],
      10_000,
      1_000,
      20_000
    );

    expect(plan).not.toBeNull();
    expect(plan?.durationMs).toBe(10_000);
    expect(plan?.startFlights[0].lat).toBe(48.8560);
    expect(plan?.startFlights[0].lon).toBe(2.3510);
    expect(plan?.startFlights[0].heading).toBe(175);
    expect(plan?.startFlights[0].speed).toBe(210);
    expect(plan?.startFlights[0].altitude).toBe(9_900);
    expect(plan?.startFlights[0].lastSeen).toBe(1_700_000_000);
  });

  it('falls back to configured duration when previous timestamps are missing', () => {
    const plan = buildBootstrapMotionPlan(
      [
        flightFixture({
          prevLat: 48.8560,
          prevLon: 2.3510,
          prevLastSeen: null
        })
      ],
      8_000,
      1_000,
      20_000
    );

    expect(plan).not.toBeNull();
    expect(plan?.durationMs).toBe(8_000);
  });
});
