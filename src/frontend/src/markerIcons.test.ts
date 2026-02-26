import type { DivIcon, DivIconOptions } from 'leaflet';
import { describe, expect, it, vi } from 'vitest';

import { createMarkerIconResolver, markerIconCacheKey } from './markerIcons';
import type { FlightMapItem } from './types';

function flightFixture(overrides: Partial<FlightMapItem> = {}): FlightMapItem {
  return {
    icao24: 'abc123',
    callsign: 'AFR123',
    lat: 48.8566,
    lon: 2.3522,
    heading: 90,
    lastSeen: 1_700_000_000,
    speed: 200,
    altitude: 10000,
    militaryHint: false,
    airframeType: 'airplane',
    fleetType: 'commercial',
    aircraftSize: 'medium',
    ...overrides
  };
}

describe('marker icon resolver', () => {
  it('reuses same icon instance for same visual identity, including heading changes', () => {
    const createDivIcon = vi.fn((options: DivIconOptions) => ({ options } as unknown as DivIcon));
    const resolveMarkerIcon = createMarkerIconResolver(createDivIcon);

    const first = resolveMarkerIcon({
      flight: flightFixture({ heading: 10 }),
      selected: false,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });
    const second = resolveMarkerIcon({
      flight: flightFixture({ heading: 220 }),
      selected: false,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });

    expect(first).toBe(second);
    expect(createDivIcon).toHaveBeenCalledTimes(1);
    const options = createDivIcon.mock.calls[0][0];
    expect(options.html).toContain('aircraft-rotator');
    expect(options.html).not.toContain('rotate(');
  });

  it('creates a new icon when visual identity changes', () => {
    const createDivIcon = vi.fn((options: DivIconOptions) => ({ options } as unknown as DivIcon));
    const resolveMarkerIcon = createMarkerIconResolver(createDivIcon);

    resolveMarkerIcon({
      flight: flightFixture(),
      selected: false,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });
    resolveMarkerIcon({
      flight: flightFixture(),
      selected: true,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });

    expect(createDivIcon).toHaveBeenCalledTimes(2);
  });

  it('builds cache key independent from heading', () => {
    const key1 = markerIconCacheKey({
      flight: flightFixture({ heading: 0 }),
      selected: false,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });
    const key2 = markerIconCacheKey({
      flight: flightFixture({ heading: 180 }),
      selected: false,
      zoom: 8,
      isStatic: false,
      debugHitbox: false
    });

    expect(key1).toBe(key2);
  });
});
