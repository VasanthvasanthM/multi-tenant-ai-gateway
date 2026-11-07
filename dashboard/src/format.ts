/**
 * Display formatting.
 *
 * Separate from the components and tested directly, because every function here
 * has a case that renders wrongly rather than failing: a ratio that is already
 * a percentage, a count that needs thousands separators, a currency value small
 * enough that two decimal places shows nothing.
 */

/** A fraction in [0,1] as a percentage. */
export function formatRatio(ratio: number): string {
  if (!Number.isFinite(ratio)) {
    // NaN reaches here if a caller divides by zero themselves. The server never
    // sends one, but a dash is honest where "NaN%" is alarming and "0%" is a lie.
    return '-';
  }
  return `${(ratio * 100).toFixed(1)}%`;
}

/** A count, with thousands separators. */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) {
    return '-';
  }
  return value.toLocaleString('en-US');
}

/**
 * A large count, abbreviated.
 *
 * Token counts reach eight digits, which does not fit in a tile and is unreadable
 * anyway - nobody compares 1,284,310 to 1,248,310 at a glance.
 */
export function formatCompact(value: number): string {
  if (!Number.isFinite(value)) {
    return '-';
  }
  if (Math.abs(value) < 1_000) {
    return value.toLocaleString('en-US');
  }
  return new Intl.NumberFormat('en-US', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

/**
 * US dollars.
 *
 * Four decimal places, not two. At the rates this gateway models, a day of a
 * small tenant's traffic is worth fractions of a cent - rounding to cents would
 * display $0.00 for a real saving, which reads as the cache doing nothing.
 */
export function formatUsd(value: number | undefined): string | undefined {
  if (value === undefined || !Number.isFinite(value)) {
    return undefined;
  }
  if (value >= 1) {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      maximumFractionDigits: 2,
    }).format(value);
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
  }).format(value);
}

/** An ISO instant as an hour label, in the viewer's own zone. */
export function formatHour(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

/** An ISO instant as a full local timestamp, for a table and a tooltip. */
export function formatInstant(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
