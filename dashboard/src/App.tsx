import { Suspense, lazy, useCallback, useState } from 'react';

import { endSession, hasApiKey } from './api/client';
import { toDisplayError } from './api/errors';
import { ErrorPanel } from './components/ErrorPanel';
import { SeriesTable } from './components/SeriesTable';
import { SignIn } from './components/SignIn';
import { StatTile } from './components/StatTile';
import { WindowPicker } from './components/WindowPicker';
import { formatCompact, formatCount, formatRatio, formatUsd } from './format';
import { useDarkMode } from './hooks/useDarkMode';
import { useSeriesQuery, useSummaryQuery } from './store/usageApi';
import { STATUS } from './theme';

/**
 * Where the signed-in tenant's NAME is remembered across a reload.
 *
 * Not the credential - that is an HttpOnly cookie this page cannot read. This is
 * a display label, so the dashboard does not flash a sign-in form at someone who
 * already has a valid session. Holding it grants nothing: the gateway checks the
 * cookie, and a stale entry is corrected the moment a request comes back 401.
 */
const TENANT_KEY = 'aigw.tenant';

function rememberedTenant(): string | null {
  try {
    return globalThis.sessionStorage?.getItem(TENANT_KEY) ?? null;
  } catch {
    // Storage can throw outright in a private window or with site data blocked.
    // A dashboard that cannot remember a label is fine; one that fails to render
    // because of it is not.
    return null;
  }
}

function rememberTenant(tenantId: string | null): void {
  try {
    if (tenantId === null) {
      globalThis.sessionStorage?.removeItem(TENANT_KEY);
    } else {
      globalThis.sessionStorage?.setItem(TENANT_KEY, tenantId);
    }
  } catch {
    // See above. Losing the label costs one extra sign-in after a reload.
  }
}

const DEFAULT_WINDOW_HOURS = 24;
const POLL_MS = 30_000;

/*
 * The charts are code-split, and the reason is measurable rather than stylistic.
 *
 * Recharts pulls in a set of d3 modules and took the bundle to 684 kB (203 kB
 * gzipped) - for a page whose most important content is four numbers. Splitting
 * them out means the tiles paint from a small bundle and the charts arrive
 * after, which matches what a reader looks at first anyway.
 *
 * Deliberately NOT replaced with hand-rolled SVG. That would remove the
 * dependency entirely, and it would also mean owning axis ticks, responsive
 * resizing and a tooltip layer - which is a lot of surface to maintain for a
 * saving the split already captures.
 */
const TrafficChart = lazy(() =>
  import('./components/TrafficChart').then((m) => ({ default: m.TrafficChart })),
);
const HitRatioChart = lazy(() =>
  import('./components/HitRatioChart').then((m) => ({ default: m.HitRatioChart })),
);

/**
 * Colour for the hit ratio tile.
 *
 * A status colour, so the figure reads as good or bad without the reader
 * knowing what a good hit ratio is. The thresholds come from the business case
 * in docs/architecture/scaling-model.md, which assumes 70%: below 30% the cache
 * is barely working, and every point below 70% is money the model said would be
 * saved and was not.
 */
function hitRatioAccent(ratio: number): string {
  if (ratio >= 0.6) {
    return STATUS.good;
  }
  if (ratio >= 0.3) {
    return STATUS.warning;
  }
  return STATUS.critical;
}

export interface AppProps {
  /**
   * Whether an API key is configured. Defaults to reading the environment.
   *
   * Injectable because `import.meta.env.VITE_*` is a COMPILE-TIME substitution
   * in Vite, not a runtime lookup - so a test cannot stub it, and the
   * no-key-configured branch would otherwise be unreachable from a test with a
   * key present. One optional prop is a smaller price than leaving a
   * user-visible branch untested.
   */
  readonly apiKeyConfigured?: boolean | undefined;
}

export function App({ apiKeyConfigured }: AppProps = {}) {
  const [hours, setHours] = useState<number>(DEFAULT_WINDOW_HOURS);
  const [sessionTenant, setSessionTenant] = useState<string | null>(rememberedTenant);
  const dark = useDarkMode();

  // Polling rather than a websocket. The rollup updates hourly, so anything
  // faster than this is requests spent re-reading a number that has not moved;
  // 30s is short enough that a reader who just sent traffic sees it.
  const summary = useSummaryQuery(hours, { pollingInterval: POLL_MS });
  const series = useSeriesQuery(hours, { pollingInterval: POLL_MS });

  const retry = useCallback(() => {
    void summary.refetch();
    void series.refetch();
  }, [summary, series]);

  const signedIn = useCallback((tenantId: string) => {
    rememberTenant(tenantId);
    setSessionTenant(tenantId);
  }, []);

  const signOut = useCallback(() => {
    // Fire the DELETE but do not wait for it. The cookie is cleared server-side
    // either way, and blocking the UI on a request whose failure changes nothing
    // would leave someone staring at a dashboard they asked to leave.
    void endSession();
    rememberTenant(null);
    setSessionTenant(null);
  }, []);

  // Two ways in, and the order matters. A build-time key is the developer
  // shortcut and skips the form entirely; everyone else signs in and gets a
  // cookie. Checking the key first means a developer's loop is unchanged.
  const usingBuildTimeKey = apiKeyConfigured ?? hasApiKey();
  if (!usingBuildTimeKey && sessionTenant === null) {
    return <SignIn onSignedIn={signedIn} />;
  }

  const error = summary.error ?? series.error;
  const points = series.data?.points ?? [];

  // The cookie is HttpOnly, so there is no way to ask whether it is still valid
  // except by using it. A 401 on a session we believed in means it expired or was
  // revoked, and the honest response is the sign-in form rather than an error
  // panel telling someone to check a key they never typed.
  if (
    sessionTenant !== null &&
    !usingBuildTimeKey &&
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    (error as { status: unknown }).status === 401
  ) {
    rememberTenant(null);
    return <SignIn onSignedIn={signedIn} />;
  }

  // isFetching, not isLoading. isLoading is only true on the FIRST request, so
  // using it would leave the tiles showing stale figures during every poll with
  // nothing indicating they are being refreshed.
  const busy = summary.isFetching || series.isFetching;

  return (
    <main className="app">
      <header className="app-header">
        <div>
          <h1>AI Gateway</h1>
          <p className="subtitle">
            {summary.data === undefined
              ? 'Tenant usage'
              : `Tenant ${summary.data.tenant_id} - last ${summary.data.window_hours}h`}
          </p>
        </div>
        <div className="header-controls">
          <WindowPicker value={hours} onChange={setHours} disabled={busy && points.length === 0} />
          {/* Only for a cookie session. A build-time key cannot be signed out
              of - it is baked into the bundle - and a button that silently does
              nothing is worse than no button. */}
          {sessionTenant !== null && !usingBuildTimeKey && (
            <button type="button" className="signout" onClick={signOut}>
              Sign out
            </button>
          )}
        </div>
      </header>

      {error !== undefined && (
        <ErrorPanel {...toDisplayError(error)} onRetry={retry} />
      )}

      <section className="tiles" aria-label="Headline figures">
        <StatTile
          label="Cache hit ratio"
          value={summary.data === undefined ? '-' : formatRatio(summary.data.hit_ratio)}
          detail="model assumes 70%"
          accent={summary.data === undefined ? undefined : hitRatioAccent(summary.data.hit_ratio)}
          loading={summary.isLoading}
        />
        <StatTile
          label="Requests"
          value={summary.data === undefined ? '-' : formatCount(summary.data.requests)}
          detail={
            summary.data === undefined
              ? undefined
              : `${formatCount(summary.data.cache_hits)} from cache`
          }
          loading={summary.isLoading}
        />
        <StatTile
          label="Tokens saved"
          value={summary.data === undefined ? '-' : formatCompact(summary.data.tokens_saved)}
          detail={
            summary.data === undefined
              ? undefined
              : `of ${formatCompact(summary.data.total_tokens)} metered`
          }
          loading={summary.isLoading}
        />
        {/* Rendered only when the server sent a figure. It omits the field when
            no price is configured - a self-hosted model has no per-token cost,
            so a money tile there would be a confident $0.00 rather than a
            meaningful zero. */}
        {summary.data?.estimated_saving_usd !== undefined && (
          <StatTile
            label="Estimated saving"
            value={formatUsd(summary.data.estimated_saving_usd) ?? '-'}
            detail="at the configured blended rate"
            accent={STATUS.good}
            loading={summary.isLoading}
          />
        )}
      </section>

      <section className="charts" aria-label="Usage over time">
        {/* A placeholder of the same height as the chart, so the page does not
            reflow when the chart chunk arrives. A spinner that collapses to
            zero height makes the table below it jump. */}
        <Suspense fallback={<div className="chart chart-placeholder" aria-hidden="true" />}>
          <TrafficChart points={points} dark={dark} />
        </Suspense>
        <Suspense fallback={<div className="chart chart-placeholder" aria-hidden="true" />}>
          <HitRatioChart points={points} dark={dark} />
        </Suspense>
      </section>

      <SeriesTable points={points} />

      <footer className="app-footer">
        <p>
          Figures come from the hourly usage rollup, so the current hour is still
          filling. Estimated saving is a CALCULATION from a configured blended
          token price, not a measured invoice.
        </p>
      </footer>
    </main>
  );
}
