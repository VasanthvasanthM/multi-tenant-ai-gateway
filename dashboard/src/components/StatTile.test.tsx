import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StatTile } from './StatTile';

describe('StatTile', () => {
  it('renders the label, value and detail', () => {
    render(<StatTile label="Cache hit ratio" value="70.9%" detail="model assumes 70%" />);

    expect(screen.getByText('Cache hit ratio')).toBeInTheDocument();
    expect(screen.getByText('70.9%')).toBeInTheDocument();
    expect(screen.getByText('model assumes 70%')).toBeInTheDocument();
  });

  it('shows a placeholder while loading, and keeps the tile in the layout', () => {
    render(<StatTile label="Requests" value="1,000" loading />);

    // The value is replaced, not the tile. A tile that appears and disappears
    // makes the layout jump on every poll, and the jump reads as a reload.
    expect(screen.getByTestId('tile-Requests')).toBeInTheDocument();
    expect(screen.queryByText('1,000')).not.toBeInTheDocument();
  });

  it('marks the value busy while loading, for assistive technology', () => {
    const { container } = render(<StatTile label="Requests" value="-" loading />);
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
  });

  it('applies a status colour only when one is given', () => {
    const { container: plain } = render(<StatTile label="Requests" value="12" />);
    expect(plain.querySelector('.tile-value')?.getAttribute('style')).toBeNull();

    const { container: accented } = render(
      <StatTile label="Ratio" value="70%" accent="#0ca30c" />,
    );
    expect(accented.querySelector('.tile-value')?.getAttribute('style')).toContain('color');
  });

  it('omits the detail line entirely when there is no detail', () => {
    const { container } = render(<StatTile label="Requests" value="12" />);
    expect(container.querySelector('.tile-detail')).toBeNull();
  });
});
