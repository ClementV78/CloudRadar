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
      return { wingSpan: 7.8, tailSpan: 3.8, fuselageHalf: 2.2 };
    case 'large':
      return { wingSpan: 12.6, tailSpan: 6.0, fuselageHalf: 3.0 };
    case 'heavy':
      return { wingSpan: 14.8, tailSpan: 7.3, fuselageHalf: 3.4 };
    case 'medium':
      return { wingSpan: 10.4, tailSpan: 4.9, fuselageHalf: 2.6 };
    case 'unknown':
    default:
      return { wingSpan: 9.3, tailSpan: 4.3, fuselageHalf: 2.4 };
  }
}

// --- Multi-element airplane glyphs (fuselage + wings + stabilizer) ---

interface AirplaneGeometry {
  /** Fuselage pill: top-y, bottom-y, half-width */
  fuseTop: number;
  fuseBot: number;
  fuseHalf: number;
  /** Wings: leading-edge-y at root, tip-x offset, tip leading-edge-y, chord at tip */
  wingRootY: number;
  wingTipX: number;
  wingTipY: number;
  wingChord: number;
  /** Stabilizer: same idea but smaller */
  stabRootY: number;
  stabTipX: number;
  stabTipY: number;
  stabChord: number;
}

function airplaneGeometry(
  flight: AircraftVisualInput
): AirplaneGeometry {
  const { wingSpan: ws, tailSpan: ts } = sizeFactors(flight.aircraftSize);
  const shape = resolveFleetShape(flight);

  switch (shape) {
    case 'commercial':
      return {
        fuseTop: 2, fuseBot: 37, fuseHalf: 2.6,
        wingRootY: 12, wingTipX: ws + 0.5, wingTipY: 19, wingChord: 3,
        stabRootY: 29, stabTipX: ts + 1, stabTipY: 33, stabChord: 2,
      };
    case 'military':
      return {
        fuseTop: 2, fuseBot: 36, fuseHalf: 2.8,
        wingRootY: 10, wingTipX: ws + 2, wingTipY: 22, wingChord: 4,
        stabRootY: 28, stabTipX: ts + 1.5, stabTipY: 33, stabChord: 2,
      };
    case 'private':
      return {
        fuseTop: 5, fuseBot: 36, fuseHalf: 2.4,
        wingRootY: 14, wingTipX: ws + 1, wingTipY: 14, wingChord: 5,
        stabRootY: 29, stabTipX: ts + 0.5, stabTipY: 29, stabChord: 3,
      };
    case 'rescue':
      return {
        fuseTop: 4, fuseBot: 37, fuseHalf: 2.4,
        wingRootY: 14, wingTipX: ws + 0.5, wingTipY: 14, wingChord: 5,
        stabRootY: 30, stabTipX: ts + 0.3, stabTipY: 30, stabChord: 3,
      };
    case 'unknown':
    default:
      return {
        fuseTop: 3, fuseBot: 37, fuseHalf: 2.3,
        wingRootY: 14, wingTipX: ws, wingTipY: 15, wingChord: 3.5,
        stabRootY: 30, stabTipX: ts, stabTipY: 31, stabChord: 2.5,
      };
  }
}

function airplaneGlyph(
  flight: AircraftVisualInput,
  size: number,
  color: string,
  stroke: string,
  outerStroke: string,
  outerSW: number,
  innerSW: number,
  fleetShape: FleetShape,
): string {
  const g = airplaneGeometry(flight);
  const x = 20;

  // Fuselage: pill/capsule shape
  const fusePath = `M ${x} ${g.fuseTop}
    C ${x + g.fuseHalf + 0.5} ${g.fuseTop} ${x + g.fuseHalf + 0.5} ${g.fuseTop + 3} ${x + g.fuseHalf} ${g.fuseTop + 5}
    L ${x + g.fuseHalf} ${g.fuseBot - 2}
    C ${x + g.fuseHalf} ${g.fuseBot} ${x + 0.5} ${g.fuseBot + 1} ${x} ${g.fuseBot + 1}
    C ${x - 0.5} ${g.fuseBot + 1} ${x - g.fuseHalf} ${g.fuseBot} ${x - g.fuseHalf} ${g.fuseBot - 2}
    L ${x - g.fuseHalf} ${g.fuseTop + 5}
    C ${x - g.fuseHalf - 0.5} ${g.fuseTop + 3} ${x - g.fuseHalf - 0.5} ${g.fuseTop} ${x} ${g.fuseTop}
    Z`;

  // Wings: swept trapezoid (leading edge straight to tip, trailing edge sweeps back)
  const wRootLE = g.wingRootY;
  const wRootTE = g.wingRootY + g.wingChord + 2;
  const wTipLE = g.wingTipY;
  const wTipTE = g.wingTipY + g.wingChord;
  const wingPath = `M ${x + g.fuseHalf} ${wRootLE}
    L ${x + g.wingTipX} ${wTipLE}
    L ${x + g.wingTipX} ${wTipTE}
    L ${x + g.fuseHalf} ${wRootTE}
    Z
    M ${x - g.fuseHalf} ${wRootLE}
    L ${x - g.wingTipX} ${wTipLE}
    L ${x - g.wingTipX} ${wTipTE}
    L ${x - g.fuseHalf} ${wRootTE}
    Z`;

  // Stabilizer: smaller swept trapezoid
  const sRootLE = g.stabRootY;
  const sRootTE = g.stabRootY + g.stabChord + 1;
  const sTipLE = g.stabTipY;
  const sTipTE = g.stabTipY + g.stabChord;
  const stabPath = `M ${x + g.fuseHalf} ${sRootLE}
    L ${x + g.stabTipX} ${sTipLE}
    L ${x + g.stabTipX} ${sTipTE}
    L ${x + g.fuseHalf} ${sRootTE}
    Z
    M ${x - g.fuseHalf} ${sRootLE}
    L ${x - g.stabTipX} ${sTipLE}
    L ${x - g.stabTipX} ${sTipTE}
    L ${x - g.fuseHalf} ${sRootTE}
    Z`;

  // Fleet-specific badge
  const fleetExtra = fleetShape === 'rescue'
    ? `<rect x="18.2" y="10" width="3.6" height="9" rx="0.8" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.5"/>
       <rect x="15" y="12.5" width="10" height="3.6" rx="0.8" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.5"/>`
    : fleetShape === 'military'
      ? (() => {
          const mxL = x - g.wingTipX + 2.5;
          const mxR = x + g.wingTipX - 2.5;
          const my = (g.wingTipY + g.wingTipY + g.wingChord) / 2;
          const ml = 4;
          return `<rect x="${mxL - 0.5}" y="${my - ml / 2}" width="1" height="${ml}" rx="0.5" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.5"/>
                  <path d="M ${mxL} ${my + ml / 2} l -0.7 1 l 1.4 0 Z" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.3"/>
                  <rect x="${mxR - 0.5}" y="${my - ml / 2}" width="1" height="${ml}" rx="0.5" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.5"/>
                  <path d="M ${mxR} ${my + ml / 2} l -0.7 1 l 1.4 0 Z" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.3"/>`;
        })()
      : '';

  return `
    <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true" shape-rendering="geometricPrecision">
      <path d="${fusePath}" fill="${color}" stroke="${outerStroke}" stroke-width="${outerSW}" stroke-linejoin="round"/>
      <path d="${fusePath}" fill="none" stroke="${stroke}" stroke-width="${innerSW}" stroke-linejoin="round"/>
      <path d="${wingPath}" fill="${color}" stroke="${outerStroke}" stroke-width="${outerSW - 0.5}" stroke-linejoin="round"/>
      <path d="${wingPath}" fill="none" stroke="${stroke}" stroke-width="${innerSW - 0.4}" stroke-linejoin="round"/>
      <path d="${stabPath}" fill="${color}" stroke="${outerStroke}" stroke-width="${outerSW - 0.7}" stroke-linejoin="round"/>
      <path d="${stabPath}" fill="none" stroke="${stroke}" stroke-width="${innerSW - 0.5}" stroke-linejoin="round"/>
      ${fleetExtra}
    </svg>
  `;
}

export function markerGlyph(flight: AircraftVisualInput, size: number, selected: boolean): string {
  const color = markerColor(flight);
  const stroke = markerStroke(selected);
  const outerStroke = '#041320';
  const outerStrokeWidth = selected ? 3.8 : 3.1;
  const innerStrokeWidth = selected ? 2.4 : 1.9;
  const fleetShape = resolveFleetShape(flight);
  const isRescue = fleetShape === 'rescue';

  if (flight.airframeType !== 'helicopter') {
    return airplaneGlyph(flight, size, color, stroke, outerStroke, outerStrokeWidth, innerStrokeWidth, fleetShape);
  }

  const rescueBadge = isRescue
      ? `
        <rect x="17.4" y="14.5" width="5.2" height="8.6" rx="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
        <rect x="15.6" y="16.3" width="8.8" height="5.0" rx="1.2" fill="${stroke}" stroke="${outerStroke}" stroke-width="0.7"/>
      `
      : '';
    const bodyPath = 'M20 9 C24 9 26.5 12 26.5 15.5 L26.5 19.5 C26.5 23.5 24 26 20 26 C16 26 13.5 23.5 13.5 19.5 L13.5 15.5 C13.5 12 16 9 20 9 Z';
    return `
      <svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true" shape-rendering="geometricPrecision">
        <path d="${bodyPath}" fill="${color}" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" />
        <path d="${bodyPath}" fill="none" stroke="${stroke}" stroke-width="${innerStrokeWidth}" />
        <path d="M20 25.5 L20 34.5" stroke="${outerStroke}" stroke-width="${outerStrokeWidth}" stroke-linecap="round" />
        <path d="M20 25.5 L20 34.5" stroke="${color}" stroke-width="${innerStrokeWidth}" stroke-linecap="round" />
        <line x1="15" y1="35" x2="25" y2="35" stroke="${outerStroke}" stroke-width="${outerStrokeWidth - 0.4}" stroke-linecap="round" />
        <line x1="15" y1="35" x2="25" y2="35" stroke="${color}" stroke-width="${innerStrokeWidth - 0.2}" stroke-linecap="round" />
        <line x1="6" y1="5" x2="34" y2="21" stroke="${outerStroke}" stroke-width="${outerStrokeWidth - 0.3}" stroke-linecap="round" />
        <line x1="6" y1="5" x2="34" y2="21" stroke="${color}" stroke-width="${innerStrokeWidth}" stroke-linecap="round" />
        <line x1="34" y1="5" x2="6" y2="21" stroke="${outerStroke}" stroke-width="${outerStrokeWidth - 0.3}" stroke-linecap="round" />
        <line x1="34" y1="5" x2="6" y2="21" stroke="${color}" stroke-width="${innerStrokeWidth}" stroke-linecap="round" />
        <circle cx="20" cy="13" r="2.5" fill="${outerStroke}" />
        <circle cx="20" cy="13" r="1.5" fill="${color}" />
        ${rescueBadge}
      </svg>
    `;
}
