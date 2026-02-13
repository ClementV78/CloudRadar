import type { ApiStatus, OpenSkyStatus } from '../types';

interface HeaderProps {
  apiStatus: ApiStatus;
  openSkyStatus: OpenSkyStatus;
  utcClock: string;
  refreshedAt: string | null;
  activeAircraft: number;
  mapTheme: 'dark' | 'satellite';
  onThemeChange: (theme: 'dark' | 'satellite') => void;
}

function badgeClass(status: ApiStatus | OpenSkyStatus): string {
  return `status-badge status-${status}`;
}

function prettyStatus(status: ApiStatus | OpenSkyStatus): string {
  switch (status) {
    case 'online':
      return 'ONLINE';
    case 'degraded':
      return 'DEGRADED';
    case 'offline':
      return 'OFFLINE';
    case 'stale':
      return 'STALE';
    default:
      return 'UNKNOWN';
  }
}

export function Header({
  apiStatus,
  openSkyStatus,
  utcClock,
  refreshedAt,
  activeAircraft,
  mapTheme,
  onThemeChange
}: HeaderProps): JSX.Element {
  return (
    <header className="top-header glass-panel">
      <div className="brand-block">
        <span className="brand-title">CloudRadar</span>
        <span className="brand-subtitle">Live IDF Air Traffic Console</span>
      </div>

      <div className="status-grid">
        <div className="status-item">
          <span className="status-label">Dashboard API</span>
          <span className={badgeClass(apiStatus)}>{prettyStatus(apiStatus)}</span>
        </div>
        <div className="status-item">
          <span className="status-label">OpenSky Feed</span>
          <span className={badgeClass(openSkyStatus)}>{prettyStatus(openSkyStatus)}</span>
        </div>
        <div className="status-item">
          <span className="status-label">Active aircraft</span>
          <span className="status-value">{activeAircraft}</span>
        </div>
        <div className="status-item">
          <span className="status-label">UTC</span>
          <span className="status-value">{utcClock}</span>
        </div>
      </div>

      <div className="meta-block">
        <div className="theme-switch">
          <button
            type="button"
            className={mapTheme === 'dark' ? 'theme-btn active' : 'theme-btn'}
            onClick={() => onThemeChange('dark')}
          >
            dark
          </button>
          <button
            type="button"
            className={mapTheme === 'satellite' ? 'theme-btn active' : 'theme-btn'}
            onClick={() => onThemeChange('satellite')}
          >
            satellite
          </button>
        </div>
        <span className="meta-pill">alerts/zones pending (#128/#424)</span>
        <span className="meta-updated">{refreshedAt ? `Last refresh ${refreshedAt} UTC` : 'Waiting for first refresh'}</span>
      </div>
    </header>
  );
}
