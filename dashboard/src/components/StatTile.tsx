import type { ReactNode } from 'react';

export interface StatTileProps {
  /*
   * Optional props are typed `T | undefined` rather than just `T?`.
   *
   * exactOptionalPropertyTypes is on, which distinguishes "property absent"
   * from "property present with value undefined". The callers here genuinely
   * pass undefined - a tile renders before its data arrives - so the type has
   * to admit it. Writing `T?` alone would force every caller into conditional
   * spreading, which is noise standing in for a type.
   */
  readonly label: string;
  readonly value: string;
  /** Shown under the value - a unit, a comparison, or the caveat on a figure. */
  readonly detail?: string | undefined;
  /** A status colour, for a figure that can be good or bad. Omit for neutral. */
  readonly accent?: string | undefined;
  readonly loading?: boolean | undefined;
  readonly children?: ReactNode | undefined;
}

/**
 * One headline number.
 *
 * A stat tile rather than a chart, because these answer "what is it now" and a
 * single number answers that better than a line with one meaningful point on it.
 *
 * `loading` renders a placeholder of the same SIZE rather than hiding the tile.
 * A tile that appears and disappears makes the layout jump on every poll, and
 * the jump reads as a page reload.
 */
export function StatTile({
  label,
  value,
  detail,
  accent,
  loading = false,
  children,
}: StatTileProps) {
  return (
    <div className="tile" data-testid={`tile-${label}`}>
      <div className="tile-label">{label}</div>
      <div
        className="tile-value"
        style={accent === undefined ? undefined : { color: accent }}
        aria-busy={loading}
      >
        {loading ? <span className="skeleton" aria-hidden="true" /> : value}
      </div>
      {detail !== undefined && <div className="tile-detail">{detail}</div>}
      {children}
    </div>
  );
}
