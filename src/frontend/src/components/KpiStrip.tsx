import type { FlightMapItem, FlightsMetricsResponse, TypeBreakdownItem } from '../types';

interface KpiStripProps {
  flights: FlightMapItem[];
  metrics: FlightsMetricsResponse | null;
}

interface ChartPoint {
  epoch: number;
  count: number;
  hasData: boolean;
}

function fillMissingWithAverage(points: ChartPoint[]): { points: ChartPoint[]; imputedBuckets: number } {
  const known = points.filter((point) => point.hasData).map((point) => point.count);
  if (known.length === 0) {
    return { points, imputedBuckets: 0 };
  }
  const average = known.reduce((sum, value) => sum + value, 0) / known.length;
  let imputedBuckets = 0;
  const filled = points.map((point) => {
    if (point.hasData) {
      return point;
    }
    imputedBuckets += 1;
    return { ...point, count: Math.max(0, Math.round(average)) };
  });
  return { points: filled, imputedBuckets };
}

function formatNumber(value: number, digits = 0): string {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(value);
}

function formatCompactNumber(value: number, digits = 0): string {
  const abs = Math.abs(value);
  if (abs >= 1_000_000) {
    return `${formatNumber(value / 1_000_000, Math.max(1, digits))}M`;
  }
  if (abs >= 1_000) {
    return `${formatNumber(value / 1_000, Math.max(1, digits))}k`;
  }
  return formatNumber(value, digits);
}

function formatAxisTime(epochSeconds: number): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(epochSeconds * 1000));
}

function formatWindowLabel(seconds: number): string {
  if (seconds <= 0) {
    return 'window';
  }
  if (seconds % 3600 === 0) {
    return `last ${seconds / 3600}h`;
  }
  if (seconds % 60 === 0) {
    return `last ${seconds / 60}m`;
  }
  return `last ${seconds}s`;
}

function safeCount(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function axisDigits(min: number, max: number): number {
  const span = Math.abs(max - min);
  if (span < 5) {
    return 1;
  }
  if (span < 20) {
    return 1;
  }
  return 0;
}

function AreaChart({
  points,
  variant,
  windowLabel
}: {
  points: Array<{ epoch: number; count: number }>;
  variant: 'cyan' | 'red';
  windowLabel: string;
}): JSX.Element {
  if (points.length < 2) {
    return <div className="area-chart-empty">insufficient points</div>;
  }

  const max = Math.max(...points.map((point) => point.count), 1);
  const min = Math.min(...points.map((point) => point.count), 0);
  const span = Math.max(max - min, 1);
  const top = 8;
  const bottom = 80;
  const left = 20;
  const right = 97;
  const width = right - left;
  const height = bottom - top;

  const lineCommands = points
    .map((point, index) => {
      const x = left + (index / (points.length - 1)) * width;
      const y = bottom - ((point.count - min) / span) * height;
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(' ');
  const areaCommands = `${lineCommands} L ${right} ${bottom} L ${left} ${bottom} Z`;
  const mid = min + span / 2;
  const digits = axisDigits(min, max);
  const firstEpoch = points[0]?.epoch ?? 0;
  const midEpoch = points[Math.floor((points.length - 1) / 2)]?.epoch ?? firstEpoch;
  const lastEpoch = points[points.length - 1]?.epoch ?? firstEpoch;

  return (
    <svg className={`area-chart area-chart-${variant}`} viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
      <line className="area-chart-grid" x1={left} y1={top} x2={right} y2={top} />
      <line className="area-chart-grid" x1={left} y1={(top + bottom) / 2} x2={right} y2={(top + bottom) / 2} />
      <line className="area-chart-grid" x1={left} y1={bottom} x2={right} y2={bottom} />
      <path className="area-chart-fill" d={areaCommands} />
      <path className="area-chart-line" d={lineCommands} />
      <text className="area-chart-ylabel" textAnchor="end" x={left - 2} y={top + 1}>{formatCompactNumber(max, digits)}</text>
      <text className="area-chart-ylabel" textAnchor="end" x={left - 2} y={(top + bottom) / 2 + 1}>{formatCompactNumber(mid, digits)}</text>
      <text className="area-chart-ylabel" textAnchor="end" x={left - 2} y={bottom - 1}>{formatCompactNumber(min, digits)}</text>
      <text className="area-chart-xlabel" textAnchor="start" x={left + 1} y={95}>{formatAxisTime(firstEpoch)}</text>
      <text className="area-chart-xlabel" textAnchor="middle" x={(left + right) / 2} y={95}>{windowLabel}</text>
      <text className="area-chart-xlabel" textAnchor="end" x={right - 1} y={95}>{formatAxisTime(lastEpoch)}</text>
    </svg>
  );
}

function TrendBlock({
  title,
  subtitle,
  points,
  variant,
  footer,
  windowLabel
}: {
  title: string;
  subtitle: string;
  points: Array<{ epoch: number; count: number }>;
  variant: 'cyan' | 'red';
  footer: string;
  windowLabel: string;
}): JSX.Element {
  return (
    <div className="kpi-trend-block">
      <div className="kpi-trend-title">{title}</div>
      <div className="kpi-trend-subtitle">{subtitle}</div>
      <AreaChart points={points} variant={variant} windowLabel={windowLabel} />
      <div className="kpi-trend-footer">{footer}</div>
    </div>
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
  centerBottom,
  compactLabel = false
}: {
  value: number;
  max: number;
  colorClass: 'ring-cyan' | 'ring-red' | 'ring-amber';
  centerTop: string;
  centerBottom: string;
  compactLabel?: boolean;
}): JSX.Element {
  const safeMax = Math.max(max, 1);
  const ratio = Math.max(0, Math.min(value / safeMax, 1));
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = circumference * (1 - ratio);

  return (
    <div className="kpi-ring-wrap">
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
        </div>
      </div>
      <span className={compactLabel ? 'kpi-ring-label compact' : 'kpi-ring-label'}>{centerBottom}</span>
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
  const activityPoints = metrics?.activitySeries ?? [];
  const totalAircraftSeriesRaw = activityPoints.map((point) => ({
    epoch: point.epoch,
    count: safeCount(point.aircraftTotal ?? point.eventsTotal),
    hasData: typeof point.hasData === 'boolean'
      ? point.hasData
      : safeCount(point.aircraftTotal ?? point.eventsTotal) > 0
  }));
  const militaryAircraftSeriesRaw = activityPoints.map((point) => ({
    epoch: point.epoch,
    count: safeCount(point.aircraftMilitary ?? point.eventsMilitary),
    hasData: typeof point.hasData === 'boolean'
      ? point.hasData
      : safeCount(point.aircraftMilitary ?? point.eventsMilitary) > 0
  }));
  const totalAircraftSeriesFilled = fillMissingWithAverage(totalAircraftSeriesRaw);
  const militaryAircraftSeriesFilled = fillMissingWithAverage(militaryAircraftSeriesRaw);
  const totalAircraftSeries = totalAircraftSeriesFilled.points;
  const militaryAircraftSeries = militaryAircraftSeriesFilled.points;
  const totalEventsWindow = activityPoints.reduce((sum, point) => sum + safeCount(point.eventsTotal), 0);
  const militaryEventsWindow = activityPoints.reduce((sum, point) => sum + safeCount(point.aircraftMilitary), 0);
  const maxActivity = Math.max(...totalAircraftSeries.map((point) => point.count), activeAircraft, 1);
  const windowLabel = formatWindowLabel(metrics?.activityWindowSeconds ?? 0);

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
            <div className="kpi-main-row">
              <div>
                <div className="kpi-main">{formatNumber(trafficDensity, 1)}</div>
                <div className="kpi-sub">density / 10k km2</div>
                <div className="kpi-sub">peak window: {formatNumber(maxActivity)}</div>
                <div className="kpi-sub">{formatNumber(totalEventsWindow)} events ({windowLabel})</div>
                <div className="kpi-sub">
                  OpenSky credits/request (24h): {openSkyCreditsPerRequest24h === null ? 'n/a' : formatNumber(openSkyCreditsPerRequest24h, 2)}
                </div>
              </div>
              <TrendBlock
                title={`Aircraft throughput (${windowLabel})`}
                subtitle="unique aircraft per bucket"
                points={totalAircraftSeries}
                variant="cyan"
                footer={`${formatNumber(activeAircraft)} aircraft now${totalAircraftSeriesFilled.imputedBuckets > 0 ? ` · ${totalAircraftSeriesFilled.imputedBuckets} gap(s) avg-filled` : ''}`}
                windowLabel={windowLabel}
              />
            </div>
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
            <div className="kpi-main-row">
              <div>
                <div className="kpi-main kpi-danger">{formatNumber(defenseScore, 1)}</div>
                <div className="kpi-sub">defense activity score</div>
                <div className="kpi-sub">military share: {compactPercent(militaryShare)}</div>
                <div className="kpi-sub">{formatNumber(militaryEventsWindow)} military aircraft ({windowLabel})</div>
              </div>
              <TrendBlock
                title={`Military aircraft (${windowLabel})`}
                subtitle="unique military aircraft per bucket"
                points={militaryAircraftSeries}
                variant="red"
                footer={`${compactPercent(militaryShare)} military share${militaryAircraftSeriesFilled.imputedBuckets > 0 ? ` · ${militaryAircraftSeriesFilled.imputedBuckets} gap(s) avg-filled` : ''}`}
                windowLabel={windowLabel}
              />
            </div>
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
                compactLabel
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
