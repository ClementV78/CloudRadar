import { markerBaseSize, markerGlyph } from '../aircraftVisuals';
import type { AircraftVisualInput } from '../aircraftVisuals';

interface LegendMarkerProps {
  profile: AircraftVisualInput;
  size: number;
  selected?: boolean;
}

function LegendMarker({ profile, size, selected = false }: LegendMarkerProps): JSX.Element {
  const svgMarkup = markerGlyph(profile, size, selected);

  return (
    <span
      className="legend-marker"
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: svgMarkup }}
    />
  );
}

const AIRPLANE_COMMERCIAL: AircraftVisualInput = {
  militaryHint: false,
  fleetType: 'commercial',
  airframeType: 'airplane',
  aircraftSize: 'medium'
};

const HELICOPTER_COMMERCIAL: AircraftVisualInput = {
  militaryHint: false,
  fleetType: 'commercial',
  airframeType: 'helicopter',
  aircraftSize: 'medium'
};

export function MapLegend(): JSX.Element {
  const legendScale = 1.1;
  const legendSize = (kind: 'small' | 'medium' | 'large' | 'heavy'): number =>
    Math.round(markerBaseSize(kind) * legendScale);

  return (
    <aside className="map-legend glass-panel" aria-label="Aircraft legend">
      <h3>Map legend</h3>
      <div className="legend-grid">
        <div className="legend-section">
          <p className="legend-title">Fleet colors</p>
          <ul className="legend-list">
            <li className="legend-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, fleetType: 'commercial' }} size={34} />
              <span>Commercial</span>
            </li>
            <li className="legend-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, fleetType: 'military', militaryHint: true }} size={34} />
              <span>Military</span>
            </li>
            <li className="legend-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, fleetType: 'private' }} size={34} />
              <span>Private</span>
            </li>
            <li className="legend-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, fleetType: 'rescue' }} size={34} />
              <span>Rescue</span>
            </li>
            <li className="legend-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, fleetType: 'unknown' }} size={34} />
              <span>Unknown</span>
            </li>
          </ul>
        </div>

        <div className="legend-section">
          <p className="legend-title">Airframe</p>
          <ul className="legend-list">
            <li className="legend-item">
              <LegendMarker profile={AIRPLANE_COMMERCIAL} size={34} />
              <span>Airplane</span>
            </li>
            <li className="legend-item">
              <LegendMarker profile={HELICOPTER_COMMERCIAL} size={34} />
              <span>Helicopter</span>
            </li>
          </ul>
          <p className="legend-note">Selected aircraft gets a white inner outline.</p>
          <div className="legend-selected-preview">
            <LegendMarker profile={AIRPLANE_COMMERCIAL} size={34} selected />
            <span>Selected preview</span>
          </div>
        </div>

        <div className="legend-section legend-section-full">
          <p className="legend-title">Aircraft size icons</p>
          <ul className="legend-size-list">
            <li className="legend-size-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, aircraftSize: 'small' }} size={legendSize('small')} />
              <span>Small</span>
            </li>
            <li className="legend-size-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, aircraftSize: 'medium' }} size={legendSize('medium')} />
              <span>Medium</span>
            </li>
            <li className="legend-size-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, aircraftSize: 'large' }} size={legendSize('large')} />
              <span>Large</span>
            </li>
            <li className="legend-size-item">
              <LegendMarker profile={{ ...AIRPLANE_COMMERCIAL, aircraftSize: 'heavy' }} size={legendSize('heavy')} />
              <span>Heavy</span>
            </li>
          </ul>
        </div>
      </div>
    </aside>
  );
}
