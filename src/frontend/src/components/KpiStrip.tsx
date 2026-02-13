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

function topAircraftTypes(items: TypeBreakdownItem[] | undefined): TypeBreakdownItem[] {
  if (!items?.length) {
    return [];
  }
  return [...items].sort((left, right) => right.count - left.count).slice(0, 3);
}

export function KpiStrip({ flights, metrics }: KpiStripProps): JSX.Element {
  const activeAircraft = metrics?.activeAircraft ?? flights.length;
  const trafficDensity = metrics?.trafficDensityPer10kKm2 ?? 0;
  const militaryShare = metrics?.militarySharePercent ?? 0;
  const defenseScore = metrics?.defenseActivityScore ?? militaryShare;
  const topTypes = topAircraftTypes(metrics?.aircraftTypes);

  return (
    <section className="kpi-strip">
      <article className="kpi-card glass-panel">
        <h3>Traffic intensity</h3>
        <div className="kpi-main">{formatNumber(activeAircraft)}</div>
        <div className="kpi-sub">active aircraft in bbox</div>
        <div className="kpi-sub">density: {formatNumber(trafficDensity, 1)} / 10k km2</div>
        <Sparkline points={metrics?.activitySeries ?? []} />
      </article>

      <article className="kpi-card glass-panel">
        <h3>Military hint activity</h3>
        <div className="kpi-main kpi-danger">{formatNumber(militaryShare, 1)}%</div>
        <div className="kpi-sub">share of active flights tagged with military hint</div>
        <div className="kpi-sub">defense activity score: {formatNumber(defenseScore, 1)}</div>
      </article>

      <article className="kpi-card glass-panel">
        <h3>Fleet mix</h3>
        {topTypes.length === 0 ? (
          <div className="kpi-sub">no type breakdown yet</div>
        ) : (
          <ul className="mix-list">
            {topTypes.map((entry) => (
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
        )}
      </article>
    </section>
  );
}
