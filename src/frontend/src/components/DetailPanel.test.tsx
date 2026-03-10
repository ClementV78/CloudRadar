import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DetailPanel } from './DetailPanel';
import type { FlightDetailResponse } from '../types';

const baseDetail: FlightDetailResponse = {
  icao24: 'abc123',
  callsign: 'AFR123',
  registration: 'F-ABCD',
  manufacturer: 'Airbus',
  model: 'A320',
  typecode: 'A320',
  category: 'A3',
  lat: 48.8566,
  lon: 2.3522,
  heading: 180,
  altitude: 10200,
  groundSpeed: 250,
  verticalRate: null,
  lastSeen: 1_700_000_000,
  onGround: false,
  country: 'FR',
  militaryHint: false,
  yearBuilt: 2015,
  ownerOperator: 'Air France',
  photo: null,
  recentTrack: [],
  timestamp: '2026-03-10T12:00:00Z'
};

describe('DetailPanel', () => {
  it('renders loading, error and empty states', () => {
    const onClose = vi.fn();
    const { rerender } = render(
      <DetailPanel detail={null} fleetType={null} open={true} loading={true} error={null} onClose={onClose} />
    );
    expect(screen.getByText('loading detail...')).toBeInTheDocument();

    rerender(
      <DetailPanel
        detail={null}
        fleetType={null}
        open={true}
        loading={false}
        error={'api down'}
        onClose={onClose}
      />
    );
    expect(screen.getByText('api down')).toBeInTheDocument();

    rerender(
      <DetailPanel detail={null} fleetType={null} open={true} loading={false} error={null} onClose={onClose} />
    );
    expect(screen.getByText('select an aircraft')).toBeInTheDocument();
  });

  it('renders detail values and formatting helpers', () => {
    render(
      <DetailPanel
        detail={{ ...baseDetail, callsign: '', groundSpeed: 200, militaryHint: null }}
        fleetType={' '}
        open={true}
        loading={false}
        error={null}
        onClose={vi.fn()}
      />
    );

    expect(screen.getAllByText('n/a').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/370\.4 km\/h/)).toBeInTheDocument();
    expect(screen.getAllByText('unknown').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('last seen')).toBeInTheDocument();
    expect(screen.getByText(/UTC/)).toBeInTheDocument();
  });

  it('renders photo status fallback messages', () => {
    const { rerender } = render(
      <DetailPanel
        detail={{ ...baseDetail, photo: { ...basePhoto(), status: 'not_found' } }}
        fleetType={'commercial'}
        open={true}
        loading={false}
        error={null}
        onClose={vi.fn()}
      />
    );
    expect(screen.getByText('no photo found')).toBeInTheDocument();

    rerender(
      <DetailPanel
        detail={{ ...baseDetail, photo: { ...basePhoto(), status: 'rate_limited' } }}
        fleetType={'commercial'}
        open={true}
        loading={false}
        error={null}
        onClose={vi.fn()}
      />
    );
    expect(screen.getByText('photo lookup rate-limited')).toBeInTheDocument();

    rerender(
      <DetailPanel
        detail={{ ...baseDetail, photo: { ...basePhoto(), status: 'error' } }}
        fleetType={'commercial'}
        open={true}
        loading={false}
        error={null}
        onClose={vi.fn()}
      />
    );
    expect(screen.getByText('photo temporarily unavailable')).toBeInTheDocument();
  });

  it('opens and closes photo modal, handles image errors and close action', () => {
    const onClose = vi.fn();
    render(
      <DetailPanel
        detail={{
          ...baseDetail,
          photo: {
            ...basePhoto(),
            status: 'available',
            thumbnailSrc: 'thumb.jpg',
            thumbnailLargeSrc: 'large.jpg',
            sourceLink: 'https://example.com/photo'
          }
        }}
        fleetType={'commercial'}
        open={true}
        loading={false}
        error={null}
        onClose={onClose}
      />
    );

    const openBtn = screen.getByLabelText('Open large aircraft photo');
    fireEvent.click(openBtn);
    expect(screen.getByRole('dialog', { name: 'Aircraft photo preview' })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'Aircraft photo preview' })).not.toBeInTheDocument();

    // Re-open then force large image failure and close via backdrop.
    fireEvent.click(openBtn);
    const largeImg = screen.getByAltText('Aircraft abc123 large');
    fireEvent.error(largeImg);
    expect(screen.queryByRole('dialog', { name: 'Aircraft photo preview' })).not.toBeInTheDocument();

    const closeBtn = screen.getByLabelText('Close panel');
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('falls back when thumbnail image fails to load', () => {
    render(
      <DetailPanel
        detail={{
          ...baseDetail,
          photo: {
            ...basePhoto(),
            status: 'available',
            thumbnailSrc: 'thumb.jpg',
            thumbnailLargeSrc: null
          }
        }}
        fleetType={'commercial'}
        open={true}
        loading={false}
        error={null}
        onClose={vi.fn()}
      />
    );

    const thumb = screen.getByAltText('Aircraft abc123');
    fireEvent.error(thumb);
    expect(screen.getByText('n/a')).toBeInTheDocument();
  });
});

function basePhoto() {
  return {
    status: 'available' as const,
    thumbnailSrc: null,
    thumbnailWidth: null,
    thumbnailHeight: null,
    thumbnailLargeSrc: null,
    thumbnailLargeWidth: null,
    thumbnailLargeHeight: null,
    photographer: null,
    sourceLink: null
  };
}
