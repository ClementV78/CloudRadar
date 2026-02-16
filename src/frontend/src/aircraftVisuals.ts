import type { FlightMapItem } from './types';

export type AircraftVisualInput = Pick<
  FlightMapItem,
  'militaryHint' | 'fleetType' | 'airframeType' | 'aircraftSize'
>;

export function markerBaseSize(size: FlightMapItem['aircraftSize']): number {
  switch (size) {
    case 'small':
      return 16;
    case 'medium':
      return 20;
    case 'large':
      return 24;
    case 'heavy':
      return 28;
    case 'unknown':
    default:
      return 19;
  }
}

function markerColor(flight: AircraftVisualInput): string {
  if (flight.militaryHint === true || flight.fleetType === 'military') {
    return '#ff4b4b';
  }

  switch (flight.fleetType) {
    case 'commercial':
      return '#45e7ff';
    case 'rescue':
      return '#7dff7a';
    case 'private':
      return '#f8e16c';
    case 'unknown':
    default:
      return '#a7bbd6';
  }
}

function markerStroke(selected: boolean): string {
  return selected ? '#ffffff' : '#0a1722';
}

type FleetShape = 'commercial' | 'military' | 'rescue' | 'private' | 'unknown';

function resolveFleetShape(flight: AircraftVisualInput): FleetShape {
  if (flight.militaryHint === true || flight.fleetType === 'military') {
    return 'military';
  }
  if (flight.fleetType === 'rescue') {
    return 'rescue';
  }
  if (flight.fleetType === 'commercial') {
    return 'commercial';
  }
  if (flight.fleetType === 'private') {
    return 'private';
  }
  return 'unknown';
}

function sizeFactors(size: FlightMapItem['aircraftSize']): {
  wingSpan: number;
  tailSpan: number;
  fuselageHalf: number;
} {
  switch (size) {
    case 'small':
      return { wingSpan: 7.8, tailSpan: 3.8, fuselageHalf: 1.9 };
    case 'large':
      return { wingSpan: 12.6, tailSpan: 6.0, fuselageHalf: 2.7 };
    case 'heavy':
      return { wingSpan: 14.8, tailSpan: 7.3, fuselageHalf: 3.2 };
    case 'medium':
      return { wingSpan: 10.4, tailSpan: 4.9, fuselageHalf: 2.2 };
    case 'unknown':
    default:
      return { wingSpan: 9.3, tailSpan: 4.3, fuselageHalf: 2.1 };
  }
}

function commercialPath(size: FlightMapItem['aircraftSize']): string {
  const x = 20;
  const { wingSpan, tailSpan } = sizeFactors(size);
  return `
    M ${x} 3
    L ${x + 2.2} 12
    L ${x + wingSpan} 16.8
    L ${x + wingSpan - 0.9} 20.0
    L ${x + 2.8} 18.8
    L ${x + tailSpan} 31.2
    L ${x + tailSpan - 1.0} 35.4
    L ${x + 2.4} 35.4
    L ${x + 2.4} 38
    L ${x - 2.4} 38
    L ${x - 2.4} 35.4
    L ${x - tailSpan + 1.0} 35.4
    L ${x - tailSpan} 31.2
    L ${x - 2.8} 18.8
    L ${x - wingSpan + 0.9} 20.0
    L ${x - wingSpan} 16.8
    L ${x - 2.2} 12
    Z
  `;
}

function militaryPath(size: FlightMapItem['aircraftSize']): string {
  const x = 20;
  const { wingSpan, tailSpan } = sizeFactors(size);
  return `
    M ${x} 3
    L ${x + 2.8} 10.5
    L ${x + wingSpan + 0.4} 19.5
    L ${x + wingSpan - 2.6} 22.4
    L ${x + 4.2} 20.8
    L ${x + tailSpan + 1.7} 31.8
    L ${x + 3.0} 35.4
    L ${x + 2.1} 38
    L ${x - 2.1} 38
    L ${x - 3.0} 35.4
    L ${x - tailSpan - 1.7} 31.8
    L ${x - 4.2} 20.8
    L ${x - wingSpan + 2.6} 22.4
    L ${x - wingSpan - 0.4} 19.5
    L ${x - 2.8} 10.5
    Z
  `;
}

function privatePath(size: FlightMapItem['aircraftSize']): string {
  const x = 20;
  const { wingSpan, tailSpan } = sizeFactors(size);
  return `
    M ${x} 4
    L ${x + 1.6} 12
    L ${x + wingSpan - 1.8} 14
    L ${x + wingSpan - 1.4} 17.1
    L ${x + 2.0} 16.4
    L ${x + tailSpan} 28.5
    L ${x + 1.8} 34
    L ${x + 1.8} 38
    L ${x - 1.8} 38
    L ${x - 1.8} 34
    L ${x - tailSpan} 28.5
    L ${x - 2.0} 16.4
    L ${x - wingSpan + 1.4} 17.1
    L ${x - wingSpan + 1.8} 14
    L ${x - 1.6} 12
    Z
  `;
}

function rescuePath(size: FlightMapItem['aircraftSize']): string {
  const x = 20;
  const { wingSpan, tailSpan } = sizeFactors(size);
  return `
    M ${x} 3.5
    L ${x + 2.1} 11.5
    L ${x + wingSpan - 0.7} 16
    L ${x + wingSpan - 0.2} 19.1
    L ${x + 2.7} 18.1
    L ${x + tailSpan + 0.4} 30
    L ${x + 2.4} 35
    L ${x + 2.4} 38
    L ${x - 2.4} 38
    L ${x - 2.4} 35
    L ${x - tailSpan - 0.4} 30
    L ${x - 2.7} 18.1
    L ${x - wingSpan + 0.2} 19.1
    L ${x - wingSpan + 0.7} 16
    L ${x - 2.1} 11.5
    Z
  `;
}

function unknownPath(size: FlightMapItem['aircraftSize']): string {
  const x = 20;
  const { wingSpan, tailSpan } = sizeFactors(size);
  return `
    M ${x} 4
    L ${x + 2} 12
    L ${x + wingSpan - 0.6} 18
    L ${x + 4} 22
    L ${x + tailSpan + 0.8} 31
    L ${x + 2} 36
    L ${x} 38
    L ${x - 2} 36
    L ${x - tailSpan - 0.8} 31
    L ${x - 4} 22
    L ${x - wingSpan + 0.6} 18
    L ${x - 2} 12
    Z
  `;
}

function airplanePath(flight: AircraftVisualInput): string {
  switch (resolveFleetShape(flight)) {
    case 'military':
      return militaryPath(flight.aircraftSize);
    case 'private':
      return privatePath(flight.aircraftSize);
    case 'rescue':
      return rescuePath(flight.aircraftSize);
    case 'unknown':
      return unknownPath(flight.aircraftSize);
    case 'commercial':
    default:
      return commercialPath(flight.aircraftSize);
  }
}

export function markerGlyph(flight: AircraftVisualInput, size: number, selected: boolean): string {
  const color = markerColor(flight);
  const stroke = markerStroke(selected);
  const outerStroke = '#041320';
  const outerStrokeWidth = selected ? 3.8 : 3.1;
  const innerStrokeWidth = selected ? 2.4 : 1.9;
  const fleetShape = resolveFleetShape(flight);
  const isRescue = fleetShape === 'rescue';

  if (flight.airframeType === 'helicopter') {
    const rescueBadge = isRescue
      ? `
        <rect x="17.4" y="15.6" width="5.2" height="9.4" rx="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
        <rect x="15.3" y="17.7" width="9.4" height="5.2" rx="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
      `
      : '';
    return `
      <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true" shape-rendering="geometricPrecision">
        <rect x="6" y="9.6" width="28" height="2.8" rx="1.4" fill="${color}" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" />
        <rect x="6" y="9.6" width="28" height="2.8" rx="1.4" fill="none" stroke="${stroke}" stroke-width="${innerStrokeWidth}" />
        <path d="M20 8.8 C23.8 8.8 26.3 11.2 26.3 15.4 L26.3 19.5 C26.3 23.8 23.9 27 20 27 C16.1 27 13.7 23.8 13.7 19.5 L13.7 15.4 C13.7 11.2 16.2 8.8 20 8.8 Z" fill="${color}" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" />
        <path d="M20 8.8 C23.8 8.8 26.3 11.2 26.3 15.4 L26.3 19.5 C26.3 23.8 23.9 27 20 27 C16.1 27 13.7 23.8 13.7 19.5 L13.7 15.4 C13.7 11.2 16.2 8.8 20 8.8 Z" fill="none" stroke="${stroke}" stroke-width="${innerStrokeWidth}" />
        <path d="M20 26.6 L20 34.6" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" stroke-linecap="round" />
        <path d="M20 26.6 L20 34.6" stroke="${stroke}" stroke-width="${innerStrokeWidth}" stroke-linecap="round" />
        <circle cx="20" cy="35.8" r="2.1" fill="${color}" stroke="${outerStroke}" stroke-width="${outerStrokeWidth - 0.3}" />
        <line x1="16.8" y1="35.8" x2="23.2" y2="35.8" stroke="${stroke}" stroke-width="${innerStrokeWidth - 0.3}" stroke-linecap="round" />
        <line x1="20" y1="32.6" x2="20" y2="39" stroke="${stroke}" stroke-width="${innerStrokeWidth - 0.3}" stroke-linecap="round" />
        ${rescueBadge}
      </svg>
    `;
  }

  const shapePath = airplanePath(flight);
  const fleetExtra = fleetShape === 'private'
    ? `
        <line x1="16.4" y1="8.4" x2="23.6" y2="8.4" stroke="${stroke}" stroke-width="1.3" stroke-linecap="round"/>
        <line x1="20" y1="6.1" x2="20" y2="10.7" stroke="${stroke}" stroke-width="1.2" stroke-linecap="round"/>
      `
    : fleetShape === 'military'
      ? `
        <circle cx="13.4" cy="20.8" r="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
        <circle cx="26.6" cy="20.8" r="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
      `
      : fleetShape === 'rescue'
        ? `
        <rect x="18" y="15" width="4" height="10" rx="1" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
        <rect x="15" y="18" width="10" height="4" rx="1" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
        `
        : fleetShape === 'commercial'
          ? `
        <circle cx="20" cy="15.4" r="1.5" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7" />
      `
      : '';

  return `
    <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true" shape-rendering="geometricPrecision">
      <path d="${shapePath}" fill="${color}" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" stroke-linejoin="round"/>
      <path d="${shapePath}" fill="none" stroke="${stroke}" stroke-width="${innerStrokeWidth}" stroke-linejoin="round"/>
      <circle cx="20" cy="13.7" r="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.6" />
      ${fleetExtra}
    </svg>
  `;
}
