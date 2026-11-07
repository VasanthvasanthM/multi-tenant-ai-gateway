import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { UsagePoint } from '../api/types';
import { formatHour, formatInstant, formatRatio } from '../format';
import { STATUS, paletteFor } from '../theme';

export interface HitRatioChartProps {
  readonly points: readonly UsagePoint[];
  readonly dark: boolean;
  /**
   * The rate the business case assumes, drawn as a reference line.
   *
   * 70% is the figure docs/architecture/scaling-model.md derives its $192M/year
   * saving from. Drawing it makes the chart answer "are we where the model
   * assumed", which is a more useful question than the bare number.
   */
  readonly targetRatio?: number | undefined;
}

/**
 * Hit ratio over time.
 *
 * Its own chart rather than a second axis on the traffic chart. A ratio and a
 * count have no common scale, and putting them on one plot with two axes makes
 * the crossover point an artefact of the axis ranges rather than anything in
 * the data.
 *
 * A single series needs no legend box - the title names it.
 */
export function HitRatioChart({ points, dark, targetRatio = 0.7 }: HitRatioChartProps) {
  const palette = paletteFor(dark);
  const data = points.map((point) => ({ hour: point.hour, ratio: point.hit_ratio }));

  return (
    <div className="chart" data-testid="hit-ratio-chart">
      <h3 className="chart-title">Cache hit ratio</h3>
      <ResponsiveContainer width="100%" height={260}>
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid stroke={palette.grid} vertical={false} />
          <XAxis
            dataKey="hour"
            tickFormatter={formatHour}
            stroke={palette.axis}
            tick={{ fontSize: 12 }}
            minTickGap={24}
          />
          {/* Fixed 0-1 domain, NOT auto-scaled.
              Auto-scaling a ratio makes a chart that ranges 0.68 to 0.71 look
              like a catastrophe, and the same chart next week look calm, with
              nothing on screen saying the axis moved. */}
          <YAxis
            domain={[0, 1]}
            ticks={[0, 0.25, 0.5, 0.75, 1]}
            tickFormatter={formatRatio}
            stroke={palette.axis}
            tick={{ fontSize: 12 }}
            width={52}
          />
          <Tooltip
            labelFormatter={(value) => formatInstant(String(value))}
            // Recharts types the value as ValueType | undefined, so it is
            // narrowed here rather than asserted. A cast would compile and then
            // render "NaN%" the first time a point is missing.
            formatter={(value) => [
              typeof value === 'number' ? formatRatio(value) : '-',
              'Hit ratio',
            ]}
            contentStyle={{
              background: palette.tooltipBackground,
              border: `1px solid ${palette.tooltipBorder}`,
              borderRadius: 6,
              fontSize: 12,
            }}
          />
          <ReferenceLine
            y={targetRatio}
            stroke={STATUS.warning}
            strokeDasharray="4 4"
            label={{
              value: `model assumes ${formatRatio(targetRatio)}`,
              position: 'insideTopRight',
              fontSize: 11,
              fill: palette.axis,
            }}
          />
          <Line
            type="monotone"
            dataKey="ratio"
            stroke={palette.requests}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, strokeWidth: 2 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
