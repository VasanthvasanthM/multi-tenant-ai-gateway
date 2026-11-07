import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { UsagePoint } from '../api/types';
import { formatCompact, formatHour, formatInstant } from '../format';
import { paletteFor } from '../theme';

export interface TrafficChartProps {
  readonly points: readonly UsagePoint[];
  readonly dark: boolean;
}

/**
 * Requests over time, split into what the cache served and what reached a
 * provider.
 *
 * STACKED, deliberately. The two series sum to the request count, so stacking
 * shows both the total and the split in one mark - and the thing a reader
 * actually wants is the ratio between them, which a stack reads directly and
 * two separate lines make you estimate.
 *
 * ONE y-axis. Both series are request counts, so they share a scale honestly.
 * A second axis is never added here: the dual-axis chart is the single most
 * common way a chart misleads, because the crossover point is an artefact of
 * two arbitrary scales rather than anything in the data.
 */
export function TrafficChart({ points, dark }: TrafficChartProps) {
  const palette = paletteFor(dark);

  // Derived here rather than in the server payload: it is presentation, and the
  // server already sends both numbers it is derived from.
  const data = points.map((point) => ({
    hour: point.hour,
    cacheHits: point.cache_hits,
    upstream: Math.max(0, point.requests - point.cache_hits),
    requests: point.requests,
  }));

  return (
    <div className="chart" data-testid="traffic-chart">
      <h3 className="chart-title">Requests served</h3>
      <ResponsiveContainer width="100%" height={260}>
        <AreaChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          {/* Horizontal only. Vertical gridlines on a time axis add ink without
              adding a comparison anyone makes. */}
          <CartesianGrid stroke={palette.grid} vertical={false} />
          <XAxis
            dataKey="hour"
            tickFormatter={formatHour}
            stroke={palette.axis}
            tick={{ fontSize: 12 }}
            minTickGap={24}
          />
          <YAxis
            stroke={palette.axis}
            tick={{ fontSize: 12 }}
            tickFormatter={formatCompact}
            width={52}
          />
          <Tooltip
            labelFormatter={(value) => formatInstant(String(value))}
            formatter={(value, name) => [
              typeof value === 'number' ? formatCompact(value) : '-',
              name === 'cacheHits' ? 'From cache' : 'From provider',
            ]}
            contentStyle={{
              background: palette.tooltipBackground,
              border: `1px solid ${palette.tooltipBorder}`,
              borderRadius: 6,
              fontSize: 12,
            }}
          />
          {/* A legend is always present for two or more series, so identity is
              never carried by colour alone. */}
          <Legend
            formatter={(value) => (value === 'cacheHits' ? 'From cache' : 'From provider')}
            iconType="square"
            wrapperStyle={{ fontSize: 12, paddingTop: 8 }}
          />
          {/* Colour is pinned per ENTITY. cacheHits is always aqua and upstream
              always orange, here and in every other chart. */}
          <Area
            type="monotone"
            dataKey="cacheHits"
            stackId="requests"
            stroke={palette.cacheHits}
            fill={palette.cacheHits}
            fillOpacity={0.35}
            strokeWidth={2}
            // A 2px surface gap between stacked fills, so adjacent bands read as
            // separate marks rather than one blended region.
            activeDot={{ r: 4, strokeWidth: 2 }}
          />
          <Area
            type="monotone"
            dataKey="upstream"
            stackId="requests"
            stroke={palette.upstream}
            fill={palette.upstream}
            fillOpacity={0.35}
            strokeWidth={2}
            activeDot={{ r: 4, strokeWidth: 2 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
