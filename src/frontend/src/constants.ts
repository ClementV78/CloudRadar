import type { Bbox } from './types';

export const IDF_BBOX: Bbox = {
  minLon: 0.9823,
  minLat: 47.9557,
  maxLon: 3.7221,
  maxLat: 49.7575
};

export const MAP_MAX_BOUNDS = {
  minLon: -0.7389,
  minLat: 46.8296,
  maxLon: 5.4433,
  maxLat: 50.8836
};

export const REFRESH_INTERVAL_MS = 10_000;
export const STALE_AFTER_SECONDS = 120;

export const API_FLIGHTS_BASE = import.meta.env.VITE_API_FLIGHTS_BASE || '/api/flights';
