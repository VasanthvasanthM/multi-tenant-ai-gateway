import { describe, expect, it } from 'vitest';

import {
  formatCompact,
  formatCount,
  formatHour,
  formatInstant,
  formatRatio,
  formatUsd,
} from './format';

describe('formatRatio', () => {
  it('renders a fraction as a percentage', () => {
    // The server sends 0-1 and the client formats. A client that assumed
    // percentages would render 7000%.
    expect(formatRatio(0.7)).toBe('70.0%');
    expect(formatRatio(0)).toBe('0.0%');
    expect(formatRatio(1)).toBe('100.0%');
  });

  it('keeps one decimal place, so a 0.5-point move is visible', () => {
    expect(formatRatio(0.7086)).toBe('70.9%');
    expect(formatRatio(0.705)).toBe('70.5%');
  });

  it('renders a dash for a non-finite value', () => {
    // The server never sends NaN - it returns 0 for an empty window - but a
    // dash is honest where "NaN%" is alarming and "0%" is a lie.
    expect(formatRatio(Number.NaN)).toBe('-');
    expect(formatRatio(Number.POSITIVE_INFINITY)).toBe('-');
  });
});

describe('formatCount', () => {
  it('adds thousands separators', () => {
    expect(formatCount(1000)).toBe('1,000');
    expect(formatCount(1284310)).toBe('1,284,310');
  });

  it('leaves small numbers alone', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(42)).toBe('42');
  });
});

describe('formatCompact', () => {
  it('abbreviates large counts', () => {
    // Token counts reach eight digits. Nobody compares 1,284,310 to 1,248,310
    // at a glance, and neither fits in a tile.
    expect(formatCompact(1_284_310)).toBe('1.3M');
    expect(formatCompact(12_000)).toBe('12K');
  });

  it('does not abbreviate below a thousand, where the exact value is readable', () => {
    expect(formatCompact(999)).toBe('999');
    expect(formatCompact(0)).toBe('0');
  });
});

describe('formatUsd', () => {
  it('uses four decimal places for sub-dollar amounts', () => {
    // Two places would show $0.00 for a real saving, which reads as the cache
    // doing nothing. At the rates this gateway models, a day of a small
    // tenant's traffic is worth fractions of a cent.
    expect(formatUsd(0.2625)).toBe('$0.2625');
    expect(formatUsd(0.0001)).toBe('$0.0001');
  });

  it('uses two decimal places once the amount is readable in cents', () => {
    expect(formatUsd(12.5)).toBe('$12.50');
    // $1.00, not $1: Intl defaults minimumFractionDigits to 2 for USD, which
    // is correct for currency - a price without cents reads as truncated.
    expect(formatUsd(1)).toBe('$1.00');
  });

  it('returns undefined when there is no figure, so the tile can be hidden', () => {
    // The server OMITS the field when no price is configured. Rendering $0.00
    // there would be a confident lie about a deployment with no per-token cost.
    expect(formatUsd(undefined)).toBeUndefined();
    expect(formatUsd(Number.NaN)).toBeUndefined();
  });
});

describe('time formatting', () => {
  it('formats an ISO instant without throwing', () => {
    expect(formatHour('2026-11-06T14:00:00Z')).toMatch(/\d/);
    expect(formatInstant('2026-11-06T14:00:00Z')).toMatch(/2026/);
  });

  it('echoes an unparseable value rather than rendering "Invalid Date"', () => {
    // A malformed timestamp is a server bug; showing the raw value makes it
    // diagnosable, where "Invalid Date" looks like a client bug.
    expect(formatHour('not-a-date')).toBe('not-a-date');
    expect(formatInstant('not-a-date')).toBe('not-a-date');
  });
});
