import L, { type DivIcon, type DivIconOptions } from 'leaflet';

import { markerBaseSize, markerGlyph } from './aircraftVisuals';
import type { FlightMapItem } from './types';

interface MarkerIconInput {
  flight: FlightMapItem;
  selected: boolean;
  zoom: number;
  isStatic: boolean;
  debugHitbox: boolean;
}

function markerOpacity(selected: boolean, isStatic: boolean): number {
  if (selected) {
    return 1;
  }
  if (isStatic) {
    return 0.45;
  }
  return 1;
}

function markerIconCacheKey({
  flight,
  selected,
  zoom,
  isStatic,
  debugHitbox
}: MarkerIconInput): string {
  return [
    flight.militaryHint === true ? 1 : 0,
    flight.fleetType,
    flight.airframeType,
    flight.aircraftSize,
    selected ? 1 : 0,
    isStatic ? 1 : 0,
    Math.round(zoom),
    debugHitbox ? 1 : 0
  ].join('|');
}

function markerIconOptions({
  flight,
  selected,
  zoom,
  isStatic,
  debugHitbox
}: MarkerIconInput): DivIconOptions {
  const baseSize = markerBaseSize(flight.aircraftSize);
  const scale = Math.min(1.75, Math.max(0.95, 1 + (zoom - 8) * 0.13));
  const rawSize = Math.round(baseSize * scale);
  const size = rawSize % 2 === 0 ? rawSize : rawSize + 1;
  const hitPadding = Math.max(6, Math.round(size * 0.24));
  const rawHitSize = size + hitPadding;
  const hitSize = rawHitSize % 2 === 0 ? rawHitSize : rawHitSize + 1;
  const pulseClass = selected ? 'marker-pulse' : '';
  const opacity = markerOpacity(selected, isStatic);
  const debugClass = debugHitbox ? ' is-debug' : '';
  const glyph = markerGlyph(flight, size, selected);

  return {
    className: 'aircraft-div-icon',
    iconSize: [hitSize, hitSize],
    iconAnchor: [Math.round(hitSize / 2), Math.round(hitSize / 2)],
    html: `
      <div class="aircraft-hitbox${debugClass}" style="width: ${hitSize}px; height: ${hitSize}px">
        <div class="aircraft-marker ${pulseClass}" style="width: ${size}px; height: ${size}px; opacity: ${opacity.toFixed(2)}">
          <div class="aircraft-rotator">
            ${glyph}
          </div>
        </div>
      </div>
    `
  };
}

export function createMarkerIconResolver(
  createDivIcon: (options: DivIconOptions) => DivIcon = (options) => L.divIcon(options)
): (input: MarkerIconInput) => DivIcon {
  const cache = new Map<string, DivIcon>();

  return (input: MarkerIconInput): DivIcon => {
    const key = markerIconCacheKey(input);
    const cached = cache.get(key);
    if (cached) {
      return cached;
    }

    const next = createDivIcon(markerIconOptions(input));
    cache.set(key, next);
    return next;
  };
}

export { markerIconCacheKey };
