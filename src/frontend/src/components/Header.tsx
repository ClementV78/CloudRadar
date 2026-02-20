import type { ApiStatus, OpenSkyStatus } from '../types';

interface HeaderProps {
  apiStatus: ApiStatus;
  openSkyStatus: OpenSkyStatus;
  utcClock: string;
  refreshedAt: string | null;
  activeAircraft: number;
  mapTheme: 'dark' | 'satellite';
  onThemeChange: (theme: 'dark' | 'satellite') => void;
  showCityLabels: boolean;
  onToggleCityLabels: () => void;
  boostActive: boolean;
  boostRemainingSeconds: number;
  boostCooldownSeconds: number;
  boostLoading: boolean;
  onTriggerBoost: () => void;
  ingesterEnabled: boolean;
  ingesterKnown: boolean;
  ingesterLoading: boolean;
  ingesterPendingTarget: 0 | 1 | null;
  onToggleIngester: (enabled: boolean) => void;
}

function formatDuration(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds));
  const mm = String(Math.floor(safe / 60)).padStart(2, '0');
  const ss = String(safe % 60).padStart(2, '0');
  return `${mm}:${ss}`;
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
  onThemeChange,
  showCityLabels,
  onToggleCityLabels,
  boostActive,
  boostRemainingSeconds,
  boostCooldownSeconds,
  boostLoading,
  onTriggerBoost,
  ingesterEnabled,
  ingesterKnown,
  ingesterLoading,
  ingesterPendingTarget,
  onToggleIngester
}: HeaderProps): JSX.Element {
  const boostDisabled = boostLoading || boostActive || boostCooldownSeconds > 0;

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
          <span className="status-label">Ingester</span>
          <label className="ingester-toggle" htmlFor="ingester-toggle">
            <input
              id="ingester-toggle"
              type="checkbox"
              aria-label="Toggle ingester on or off"
              checked={ingesterEnabled}
              disabled={ingesterLoading}
              onChange={(event) => onToggleIngester(event.target.checked)}
            />
            <span className="ingester-slider" />
            <span className={ingesterKnown ? 'status-value' : 'status-badge status-unknown'}>
              {ingesterLoading && ingesterPendingTarget !== null
                ? `applying ${ingesterPendingTarget === 1 ? 'ON' : 'OFF'}...`
                : ingesterLoading
                  ? 'updating...'
                  : ingesterKnown
                    ? (ingesterEnabled ? 'ON' : 'OFF')
                    : 'UNKNOWN'}
            </span>
          </label>
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
          <button
            type="button"
            className={showCityLabels ? 'theme-btn active' : 'theme-btn'}
            onClick={onToggleCityLabels}
          >
            labels
          </button>
          <button
            type="button"
            className={boostActive ? 'theme-btn boost-btn active' : 'theme-btn boost-btn'}
            onClick={onTriggerBoost}
            disabled={boostDisabled}
          >
            {boostLoading ? 'boosting...' : 'boost x2 (3m)'}
          </button>
        </div>
        {boostActive && <span className="meta-pill boost-pill">BBox boost active {formatDuration(boostRemainingSeconds)}</span>}
        {!boostActive && boostCooldownSeconds > 0 && (
          <span className="meta-pill boost-pill">Boost cooldown {formatDuration(boostCooldownSeconds)}</span>
        )}
        <span className="meta-pill">alerts/zones pending (#128/#424)</span>
        <span className="meta-updated">{refreshedAt ? `Last refresh ${refreshedAt} UTC` : 'Waiting for first refresh'}</span>
      </div>
    </header>
  );
}
