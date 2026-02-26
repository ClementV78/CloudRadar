import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { MapLegend } from './MapLegend';

describe('MapLegend', () => {
  it('is collapsed by default', () => {
    render(<MapLegend />);

    expect(screen.getByRole('button', { name: 'Legend' })).toBeInTheDocument();
    expect(screen.getByText('Fleet colors')).not.toBeVisible();
  });

  it('opens and closes when toggled', () => {
    render(<MapLegend />);

    fireEvent.click(screen.getByRole('button', { name: 'Legend' }));
    expect(screen.getByText('Fleet colors')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Hide legend' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close legend' }));
    expect(screen.getByText('Fleet colors')).not.toBeVisible();
  });
});
