import type { FlightDetailResponse } from '../types';

interface DetailPanelProps {
  detail: FlightDetailResponse | null;
  open: boolean;
  loading: boolean;
  error: string | null;
  onClose: () => void;
}

function formatValue(value: string | number | boolean | null | undefined, suffix = ''): string {
  if (value === null || value === undefined || value === '') {
    return 'n/a';
  }
  return `${value}${suffix}`;
}

function formatLastSeen(epoch: number | null | undefined): string {
  if (!epoch) {
    return 'n/a';
  }
  return new Date(epoch * 1000).toISOString().replace('T', ' ').replace('Z', ' UTC');
}

function militaryHintLabel(value: boolean | null | undefined): string {
  if (value === true) {
    return 'true (heuristic)';
  }
  if (value === false) {
    return 'false';
  }
  return 'unknown';
}

export function DetailPanel({ detail, open, loading, error, onClose }: DetailPanelProps): JSX.Element {
  return (
    <aside className={`detail-panel glass-panel ${open ? 'is-open' : ''}`}>
      <div className="detail-header">
        <div>
          <h2>Flight detail</h2>
          <p>click marker to inspect live metadata</p>
        </div>
        <button type="button" onClick={onClose} className="close-btn" aria-label="Close panel">
          close
        </button>
      </div>

      {loading && <div className="panel-state">loading detail...</div>}
      {!loading && error && <div className="panel-state panel-error">{error}</div>}
      {!loading && !error && !detail && <div className="panel-state">select an aircraft</div>}

      {!loading && !error && detail && (
        <dl className="detail-grid">
          <dt>icao24</dt>
          <dd>{formatValue(detail.icao24)}</dd>

          <dt>callsign</dt>
          <dd>{formatValue(detail.callsign)}</dd>

          <dt>position</dt>
          <dd>
            {formatValue(detail.lat)} / {formatValue(detail.lon)}
          </dd>

          <dt>heading</dt>
          <dd>{formatValue(detail.heading, ' deg')}</dd>

          <dt>speed</dt>
          <dd>{formatValue(detail.groundSpeed, ' kt')}</dd>

          <dt>altitude</dt>
          <dd>{formatValue(detail.altitude, ' m')}</dd>

          <dt>country</dt>
          <dd>{formatValue(detail.country)}</dd>

          <dt>typecode</dt>
          <dd>{formatValue(detail.typecode)}</dd>

          <dt>category</dt>
          <dd>{formatValue(detail.category)}</dd>

          <dt>military hint</dt>
          <dd>{militaryHintLabel(detail.militaryHint)}</dd>

          <dt>last seen</dt>
          <dd>{formatLastSeen(detail.lastSeen)}</dd>
        </dl>
      )}
    </aside>
  );
}
