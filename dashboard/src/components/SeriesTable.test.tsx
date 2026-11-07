import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { UsagePoint } from '../api/types';
import { SeriesTable } from './SeriesTable';

const points: UsagePoint[] = [
  {
    hour: '2026-11-06T00:00:00Z',
    requests: 400,
    cache_hits: 280,
    hit_ratio: 0.7,
    total_tokens: 400_000,
    upstream_tokens: 120_000,
    tokens_saved: 280_000,
  },
  {
    hour: '2026-11-06T01:00:00Z',
    requests: 600,
    cache_hits: 420,
    hit_ratio: 0.7,
    total_tokens: 600_000,
    upstream_tokens: 180_000,
    tokens_saved: 420_000,
  },
];

describe('SeriesTable', () => {
  it('renders a row per hour', () => {
    render(<SeriesTable points={points} />);

    // Header row plus two data rows.
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getByText('400')).toBeInTheDocument();
    expect(screen.getByText('600')).toBeInTheDocument();
  });

  it('shows newest first, the opposite of the chart', () => {
    render(<SeriesTable points={points} />);

    const rows = screen.getAllByRole('row');
    // A reader scanning a table wants the most recent row at the top; a chart
    // reads left to right in time. The directions differ on purpose.
    expect(rows[1]?.textContent).toContain('600');
    expect(rows[2]?.textContent).toContain('400');
  });

  it('exists to satisfy the contrast relief rule', () => {
    // Not decorative. In light mode the aqua chart series measures 2.74:1
    // against the surface, below the 3:1 floor, and the documented relief is a
    // visible label or a table view. This is the table view, so no figure is
    // ever available only as a low-contrast mark.
    render(<SeriesTable points={points} />);
    expect(screen.getByTestId('series-table')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('has a caption for screen readers', () => {
    render(<SeriesTable points={points} />);
    expect(screen.getByRole('table')).toHaveAccessibleName(/hourly usage/i);
  });

  it('says so when a window has no traffic', () => {
    render(<SeriesTable points={[]} />);
    expect(screen.getByText(/no traffic in this window/i)).toBeInTheDocument();
  });
});
