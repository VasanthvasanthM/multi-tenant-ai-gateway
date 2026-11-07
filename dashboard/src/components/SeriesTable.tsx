import type { UsagePoint } from '../api/types';
import { formatCompact, formatCount, formatInstant, formatRatio } from '../format';

export interface SeriesTableProps {
  readonly points: readonly UsagePoint[];
}

/**
 * The series as a table.
 *
 * Not a fallback - a required part of the design, for two reasons.
 *
 *   1. ACCESSIBILITY. The charts encode two series by colour. In light mode the
 *      aqua series measures 2.74:1 against the surface, below the 3:1 floor the
 *      palette validator enforces, and the documented relief for that is a
 *      visible label or a table view. This is the table view, so no figure is
 *      ever available only as a low-contrast mark.
 *
 *   2. Charts answer "what is the shape"; a table answers "what was the number
 *      at 14:00". Both questions get asked, and estimating a value off a line
 *      is how people end up quoting figures that were never in the data.
 *
 * Newest first here, the opposite of the chart. A reader scanning a table wants
 * the most recent row at the top; a chart reads left to right in time.
 */
export function SeriesTable({ points }: SeriesTableProps) {
  const rows = [...points].reverse();

  return (
    <details className="series-table" data-testid="series-table">
      <summary>Table view ({rows.length} hours)</summary>
      <div className="table-scroll">
        <table>
          <caption className="visually-hidden">
            Hourly usage: requests, cache hits, hit ratio and tokens saved.
          </caption>
          <thead>
            <tr>
              <th scope="col">Hour</th>
              <th scope="col">Requests</th>
              <th scope="col">From cache</th>
              <th scope="col">Hit ratio</th>
              <th scope="col">Tokens saved</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="table-empty">
                  No traffic in this window.
                </td>
              </tr>
            )}
            {rows.map((point) => (
              <tr key={point.hour}>
                <th scope="row">{formatInstant(point.hour)}</th>
                <td>{formatCount(point.requests)}</td>
                <td>{formatCount(point.cache_hits)}</td>
                <td>{formatRatio(point.hit_ratio)}</td>
                <td>{formatCompact(point.tokens_saved)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}
