/**
 * Chart colours.
 *
 * These are not chosen, they are VALIDATED. Both sets were checked with the
 * palette validator against their own surface:
 *
 *   light  #2a78d6 #eb6834 #1baf7a  - all checks pass
 *   dark   #3987e5 #d95926 #199e70  - all checks pass
 *
 * Two rules follow from that validation and are load-bearing:
 *
 *   1. Colour tracks the ENTITY, never the series order. `cacheHits` is always
 *      aqua and `upstream` always orange, in every chart. A palette indexed by
 *      position repaints the surviving series whenever a filter removes one,
 *      and the reader has to rebuild their mental mapping each time.
 *
 *   2. In LIGHT mode aqua sits at 2.74:1 against the surface, below the 3:1
 *      floor. The validator flags that and the relief is mandatory: the series
 *      table in SeriesTable.tsx exists partly to satisfy it, so the data is
 *      never available only as a low-contrast mark.
 */

export interface ChartPalette {
  readonly requests: string;
  readonly upstream: string;
  readonly cacheHits: string;
  readonly grid: string;
  readonly axis: string;
  readonly tooltipBackground: string;
  readonly tooltipBorder: string;
}

const LIGHT: ChartPalette = {
  requests: '#2a78d6',
  upstream: '#eb6834',
  cacheHits: '#1baf7a',
  grid: '#e8e8e4',
  axis: '#52514e',
  tooltipBackground: '#ffffff',
  tooltipBorder: '#d6d6d0',
};

const DARK: ChartPalette = {
  requests: '#3987e5',
  upstream: '#d95926',
  cacheHits: '#199e70',
  grid: '#2e2e2c',
  axis: '#c3c2b7',
  tooltipBackground: '#242423',
  tooltipBorder: '#3b3b38',
};

export function paletteFor(dark: boolean): ChartPalette {
  return dark ? DARK : LIGHT;
}

/** Status colours. Reserved - never reused as a series colour. */
export const STATUS = {
  good: '#0ca30c',
  warning: '#fab219',
  critical: '#d03b3b',
} as const;
