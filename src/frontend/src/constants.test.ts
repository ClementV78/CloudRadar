import { describe, expect, it } from 'vitest';
import { IDF_BBOX, MAP_MAX_BOUNDS, STALE_AFTER_SECONDS } from './constants';

describe('constants', () => {
  it('keeps default bbox coordinates ordered', () => {
    expect(IDF_BBOX.minLon).toBeLessThan(IDF_BBOX.maxLon);
    expect(IDF_BBOX.minLat).toBeLessThan(IDF_BBOX.maxLat);
  });

  it('keeps map bounds ordered', () => {
    expect(MAP_MAX_BOUNDS.minLon).toBeLessThan(MAP_MAX_BOUNDS.maxLon);
    expect(MAP_MAX_BOUNDS.minLat).toBeLessThan(MAP_MAX_BOUNDS.maxLat);
  });

  it('keeps stale threshold positive', () => {
    expect(STALE_AFTER_SECONDS).toBeGreaterThan(0);
  });
});
