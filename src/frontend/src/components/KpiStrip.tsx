import type { FlightMapItem, FlightsMetricsResponse, TypeBreakdownItem } from '../types';

interface KpiStripProps {
  flights: FlightMapItem[];
  metrics: FlightsMetricsResponse | null;
}

function formatNumber(value: number, digits = 0): string {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(value);
}

function Sparkline({ points }: { points: Array<{ epoch: number; count: number }> }): JSX.Element {
  if (points.length < 2) {
    return <div className="sparkline-empty">insufficient points</div>;
  }

  const max = Math.max(...points.map((point) => point.count), 1);
  const min = Math.min(...points.map((point) => point.count), 0);
  const span = Math.max(max - min, 1);

  const commands = points
    .map((point, index) => {
      const x = (index / (points.length - 1)) * 100;
      const y = 100 - ((point.count - min) / span) * 100;
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(' ');

  return (
    <svg className="sparkline" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
      <path d={commands} />
    </svg>
  );
}

function topBreakdown(items: TypeBreakdownItem[] | undefined, limit = 3): TypeBreakdownItem[] {
  if (!items?.length) {
    return [];
  }
  return [...items].sort((left, right) => right.count - left.count).slice(0, limit);
}

function RingGauge({
  value,
  max,
  colorClass,
  centerTop,
  centerBottom
}: {
  value: number;
  max: number;
  colorClass: 'ring-cyan' | 'ring-red' | 'ring-amber';
  centerTop: string;
  centerBottom: string;
}): JSX.Element {
  const safeMax = Math.max(max, 1);
  const ratio = Math.max(0, Math.min(value / safeMax, 1));
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = circumference * (1 - ratio);

  return (
    <div className={`kpi-ring ${colorClass}`}>
      <svg viewBox="0 0 92 92" aria-hidden="true">
        <circle className="kpi-ring-bg" cx="46" cy="46" r={radius} />
        <circle
          className="kpi-ring-fg"
          cx="46"
          cy="46"
          r={radius}
          style={{
            strokeDasharray: circumference,
            strokeDashoffset: dashOffset
          }}
        />
      </svg>
      <div className="kpi-ring-center">
        <strong>{centerTop}</strong>
        <span>{centerBottom}</span>
      </div>
    </div>
  );
}

function compactPercent(value: number): string {
  return `${formatNumber(value, 1)}%`;
}

function dominantLabel(items: TypeBreakdownItem[]): string {
  if (!items.length) {
    return 'n/a';
  }
  return items[0].key;
}

export function KpiStrip({ flights, metrics }: KpiStripProps): JSX.Element {
  const activeAircraft = metrics?.activeAircraft ?? flights.length;
  const trafficDensity = metrics?.trafficDensityPer10kKm2 ?? 0;
  const militaryShare = metrics?.militarySharePercent ?? 0;
  const defenseScore = metrics?.defenseActivityScore ?? militaryShare;
  const openSkyCreditsPerRequest24h = metrics?.openSkyCreditsPerRequest24h ?? null;
  const topFleet = topBreakdown(metrics?.fleetBreakdown, 3);
  const topTypes = topBreakdown(metrics?.aircraftTypes, 3);
  const topSizes = topBreakdown(metrics?.aircraftSizes, 3);
  const maxActivity = Math.max(...(metrics?.activitySeries ?? []).map((point) => point.count), activeAircraft, 1);

  return (
    <section className="kpi-strip">
      <article className="kpi-card kpi-traffic glass-panel">
        <h3>Traffic density</h3>
        <div className="kpi-card-layout">
          <RingGauge
            value={activeAircraft}
            max={maxActivity}
            colorClass="ring-cyan"
            centerTop={formatNumber(activeAircraft)}
            centerBottom="active"
          />
          <div className="kpi-panel">
            <div className="kpi-main">{formatNumber(trafficDensity, 1)}</div>
            <div className="kpi-sub">density / 10k km2</div>
            <div className="kpi-sub">peak window: {formatNumber(maxActivity)}</div>
            <div className="kpi-sub">
              OpenSky credits/request (24h): {openSkyCreditsPerRequest24h === null ? 'n/a' : formatNumber(openSkyCreditsPerRequest24h, 2)}
            </div>
            <Sparkline points={metrics?.activitySeries ?? []} />
          </div>
        </div>
      </article>

      <article className="kpi-card kpi-defense glass-panel">
        <h3>Defense activity</h3>
        <div className="kpi-card-layout">
          <RingGauge
            value={militaryShare}
            max={100}
            colorClass="ring-red"
            centerTop={compactPercent(militaryShare)}
            centerBottom="military"
          />
          <div className="kpi-panel">
            <div className="kpi-main kpi-danger">{formatNumber(defenseScore, 1)}</div>
            <div className="kpi-sub">defense activity score</div>
            <div className="kpi-sub">military share: {compactPercent(militaryShare)}</div>
            <Sparkline points={metrics?.activitySeries ?? []} />
          </div>
        </div>
      </article>

      <article className="kpi-card kpi-fleet glass-panel">
        <h3>Fleet breakdown</h3>
        {topFleet.length === 0 ? (
          <div className="kpi-sub">no fleet breakdown yet</div>
        ) : (
          <div className="fleet-grid">
            <div className="fleet-overview">
              <RingGauge
                value={topFleet[0].percent}
                max={100}
                colorClass="ring-amber"
                centerTop={compactPercent(topFleet[0].percent)}
                centerBottom={dominantLabel(topFleet)}
              />
              <div className="kpi-sub">dominant profile</div>
            </div>
            <ul className="mix-list">
              {topFleet.map((entry) => (
                <li key={entry.key}>
                  <div className="mix-head">
                    <span>{entry.key}</span>
                    <span>{formatNumber(entry.percent, 1)}%</span>
                  </div>
                  <div className="mix-bar">
                    <span style={{ width: `${Math.min(entry.percent, 100)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="kpi-chips">
          {topSizes.map((entry) => (
            <span key={`size-${entry.key}`} className="kpi-chip">
              {entry.key}: {formatNumber(entry.percent, 0)}%
            </span>
          ))}
          {topTypes.slice(0, 2).map((entry) => (
            <span key={`type-${entry.key}`} className="kpi-chip muted">
              {entry.key}
            </span>
          ))}
          {topSizes.length === 0 && topTypes.length === 0 && <span className="kpi-chip muted">awaiting data</span>}
        </div>
      </article>
    </section>
  );
}
