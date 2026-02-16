export interface Bbox {
  minLon: number;
  minLat: number;
  maxLon: number;
  maxLat: number;
}

export interface FlightMapItem {
  icao24: string;
  callsign: string | null;
  lat: number | null;
  lon: number | null;
  heading: number | null;
  lastSeen: number | null;
  speed: number | null;
  altitude: number | null;
  militaryHint: boolean | null;
  airframeType: 'airplane' | 'helicopter' | 'unknown' | null;
  fleetType: 'commercial' | 'military' | 'private' | 'unknown' | null;
  aircraftSize: 'small' | 'medium' | 'large' | 'heavy' | 'unknown' | null;
}

export interface FlightListResponse {
  items: FlightMapItem[];
  count: number;
  totalMatched: number;
  limit: number;
  bbox: Bbox;
  latestOpenSkyBatchEpoch: number | null;
  timestamp: string;
}

export interface FlightTrackPoint {
  lat: number | null;
  lon: number | null;
  heading: number | null;
  altitude: number | null;
  groundSpeed: number | null;
  lastSeen: number | null;
  onGround: boolean | null;
}

export interface FlightDetailResponse {
  icao24: string;
  callsign: string | null;
  registration: string | null;
  manufacturer: string | null;
  model: string | null;
  typecode: string | null;
  category: string | null;
  lat: number | null;
  lon: number | null;
  heading: number | null;
  altitude: number | null;
  groundSpeed: number | null;
  verticalRate: number | null;
  lastSeen: number | null;
  onGround: boolean | null;
  country: string | null;
  militaryHint: boolean | null;
  yearBuilt: number | null;
  ownerOperator: string | null;
  recentTrack: FlightTrackPoint[];
  timestamp: string;
}

export interface TypeBreakdownItem {
  key: string;
  count: number;
  percent: number;
}

export interface ActivityPoint {
  epoch: number;
  count: number;
}

export interface FlightsMetricsResponse {
  activeAircraft: number;
  trafficDensityPer10kKm2: number;
  militarySharePercent: number;
  defenseActivityScore: number;
  fleetBreakdown: TypeBreakdownItem[];
  aircraftSizes: TypeBreakdownItem[];
  aircraftTypes: TypeBreakdownItem[];
  activitySeries: ActivityPoint[];
  openSkyCreditsPerRequest24h: number | null;
  timestamp: string;
}

export interface BboxBoostStatusResponse {
  active: boolean;
  factor: number;
  bbox: Bbox;
  activeUntilEpoch: number | null;
  cooldownUntilEpoch: number | null;
  serverEpoch: number;
}

export type ApiStatus = 'online' | 'degraded' | 'offline';
export type OpenSkyStatus = 'online' | 'stale' | 'unknown';
