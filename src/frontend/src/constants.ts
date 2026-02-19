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

const REFRESH_MS_ENV = Number(import.meta.env.VITE_UI_REFRESH_MS ?? 10_000);
export const REFRESH_INTERVAL_MS = Number.isFinite(REFRESH_MS_ENV) && REFRESH_MS_ENV > 0 ? REFRESH_MS_ENV : 10_000;
export const STALE_AFTER_SECONDS = 120;

export const API_FLIGHTS_BASE = import.meta.env.VITE_API_FLIGHTS_BASE || '/api/flights';
export const ADMIN_SCALE_PATH = import.meta.env.VITE_ADMIN_SCALE_PATH || '/admin/ingester/scale';
