import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { KpiStrip } from './KpiStrip';
import type { FlightsMetricsResponse } from '../types';

const METRICS_FIXTURE: FlightsMetricsResponse = {
  activeAircraft: 42,
  trafficDensityPer10kKm2: 12.5,
  militarySharePercent: 8.2,
  defenseActivityScore: 9.1,
  fleetBreakdown: [
    { key: 'commercial', count: 30, percent: 71.4 },
    { key: 'military', count: 8, percent: 19.0 },
    { key: 'private', count: 4, percent: 9.6 }
  ],
  aircraftSizes: [
    { key: 'medium', count: 20, percent: 47.6 },
    { key: 'small', count: 12, percent: 28.6 }
  ],
  aircraftTypes: [
    { key: 'airplane', count: 35, percent: 83.3 },
    { key: 'helicopter', count: 7, percent: 16.7 }
  ],
  activitySeries: [
    {
      epoch: 1_700_000_000,
      eventsTotal: 40,
      eventsMilitary: 6,
      aircraftTotal: 38,
      aircraftMilitary: 5,
      militarySharePercent: 13.2,
      hasData: true
    },
    {
      epoch: 1_700_000_600,
      eventsTotal: 42,
      eventsMilitary: 7,
      aircraftTotal: 40,
      aircraftMilitary: 6,
      militarySharePercent: 15,
      hasData: true
    }
  ],
  activityBucketSeconds: 600,
  activityWindowSeconds: 3_600,
  openSkyCreditsPerRequest24h: 1.2,
  timestamp: '2026-02-26T00:00:00Z'
};

describe('KpiStrip tabs classes', () => {
  it('marks only selected KPI card as active when activeTab is provided', () => {
    const { container } = render(<KpiStrip flights={[]} metrics={METRICS_FIXTURE} activeTab="defense" />);

    const trafficCard = container.querySelector('#kpi-panel-traffic');
    const defenseCard = container.querySelector('#kpi-panel-defense');
    const fleetCard = container.querySelector('#kpi-panel-fleet');

    expect(trafficCard).toHaveClass('is-collapsed');
    expect(defenseCard).toHaveClass('is-active');
    expect(fleetCard).toHaveClass('is-collapsed');
    expect(screen.getByText('Defense activity')).toBeInTheDocument();
  });
});
