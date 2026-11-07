import { configureStore } from '@reduxjs/toolkit';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';
import type { UsageSeries, UsageSummary } from './api/types';
import { usageApi } from './store/usageApi';

/**
 * The whole dashboard against a mocked `fetch`.
 *
 * A fresh store per test, deliberately. RTK Query caches, so a shared store
 * would let one test's response satisfy the next test's query - and the second
 * test would pass without ever exercising the code it claims to.
 */
function renderApp(props: { apiKeyConfigured?: boolean } = {}) {
  const store = configureStore({
    reducer: { [usageApi.reducerPath]: usageApi.reducer },
    middleware: (getDefault) => getDefault().concat(usageApi.middleware),
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <App {...props} />
      </Provider>,
    ),
  };
}

const SUMMARY: UsageSummary = {
  tenant_id: 'acme',
  from: '2026-11-06T00:00:00Z',
  to: '2026-11-07T00:00:00Z',
  window_hours: 24,
  requests: 1000,
  cache_hits: 709,
  hit_ratio: 0.709,
  prompt_tokens: 600_000,
  completion_tokens: 400_000,
  total_tokens: 1_000_000,
  upstream_tokens: 300_000,
  tokens_saved: 700_000,
  estimated_saving_usd: 0.2625,
};

const SERIES: UsageSeries = {
  tenant_id: 'acme',
  window_hours: 24,
  points: [
    {
      hour: '2026-11-06T00:00:00Z',
      requests: 400,
      cache_hits: 280,
      hit_ratio: 0.7,
      total_tokens: 400_000,
      upstream_tokens: 120_000,
      tokens_saved: 280_000,
    },
  ],
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Routes by path, so a test does not depend on request order. */
function mockFetch(handler: (url: string) => Response) {
  // Both parameters declared, so a test can assert on the RequestInit - the
  // bearer header is part of the contract and worth checking.
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void init;
    // RTK Query passes a REQUEST object, not a string. String(request) gives
    // "[object Request]", so routing on it silently matched nothing and both
    // endpoints received the same response - the summary tiles then rendered
    // from the series payload, with no error anywhere.
    const url =
      typeof input === 'string'
        ? input
        : input instanceof Request
          ? input.url
          : input.toString();
    return Promise.resolve(handler(url));
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}

/*
 * The key comes from dashboard/.env.test, NOT from vi.stubEnv.
 *
 * Vite statically replaces import.meta.env.VITE_* with a literal at transform
 * time, so a runtime stub cannot change it - every App test rendered the
 * "no API key configured" panel until the value was supplied at build time
 * instead. That is a Vite property worth knowing rather than a Vitest bug.
 */
describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the headline figures once both queries resolve', async () => {
    mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(SUMMARY) : jsonResponse(SERIES),
    );

    renderApp();

    await waitFor(() => {
      expect(screen.getByText('70.9%')).toBeInTheDocument();
    });

    expect(screen.getByText('1,000')).toBeInTheDocument();
    expect(screen.getByText('700K')).toBeInTheDocument();
    expect(screen.getByText('$0.2625')).toBeInTheDocument();
    expect(screen.getByText(/Tenant acme/)).toBeInTheDocument();
  });

  it('sends the API key as a bearer token', async () => {
    const spy = mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(SUMMARY) : jsonResponse(SERIES),
    );

    renderApp();
    await waitFor(() => expect(spy).toHaveBeenCalled());

    // Read off the Request, because that is what RTK Query actually sends -
    // asserting on an init object would pass vacuously, since there is none.
    const first = spy.mock.calls[0]?.[0];
    expect(first).toBeInstanceOf(Request);
    expect((first as Request).headers.get('Authorization')).toBe('Bearer sk-test-key');
  });

  it('requests 24 hours by default and re-queries when the window changes', async () => {
    const spy = mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(SUMMARY) : jsonResponse(SERIES),
    );

    renderApp();
    await waitFor(() => expect(spy).toHaveBeenCalled());

    const urls = () =>
      spy.mock.calls.map((call) => {
        const input = call[0];
        return input instanceof Request ? input.url : String(input);
      });
    expect(urls().some((url) => url.includes('hours=24'))).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: '7d' }));

    await waitFor(() => {
      expect(urls().some((url) => url.includes('hours=168'))).toBe(true);
    });
  });

  it('hides the money tile when the server omits the figure', async () => {
    const { estimated_saving_usd: _omitted, ...withoutMoney } = SUMMARY;
    mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(withoutMoney) : jsonResponse(SERIES),
    );

    renderApp();
    await waitFor(() => expect(screen.getByText('70.9%')).toBeInTheDocument());

    // Absent, not $0.00. A self-hosted model has no per-token cost, so a money
    // tile there would be a confident lie rather than a meaningful zero.
    //
    // Matched by test id rather than by text: the footer explains that the
    // saving is a calculation, so a /Estimated saving/ text query matches the
    // footer too and the assertion passes or fails for the wrong reason.
    expect(screen.queryByTestId('tile-Estimated saving')).not.toBeInTheDocument();
    // The tile that does not depend on a price is still there.
    expect(screen.getByTestId('tile-Tokens saved')).toBeInTheDocument();
  });

  it('shows the gateway error message and offers retry only when retryable', async () => {
    mockFetch(() =>
      jsonResponse(
        {
          error: {
            message: 'circuit open for provider',
            type: 'server_error',
            code: 'circuit_open',
            retryable: true,
          },
        },
        503,
      ),
    );

    renderApp();

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
    expect(screen.getByText(/circuit open/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
  });

  it('offers no retry on an error the server marked not retryable', async () => {
    mockFetch(() =>
      jsonResponse(
        {
          error: {
            message: 'token budget exhausted',
            type: 'invalid_request_error',
            code: 'budget_exhausted',
            retryable: false,
          },
        },
        402,
      ),
    );

    renderApp();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // Offering retry on an error that can never succeed trains people to click
    // it where it cannot help, and then to ignore it where it can.
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument();
  });

  it('tells the reader to configure a key when none is set', () => {
    mockFetch(() => jsonResponse(SUMMARY));

    // Injected rather than stubbed, for the compile-time reason above.
    renderApp({ apiKeyConfigured: false });

    // Before any request. A 401 would be the alternative, and it would send
    // someone looking at the gateway instead of at their own .env.local.
    expect(screen.getByText(/no api key configured/i)).toBeInTheDocument();
    expect(screen.getByText(/VITE_API_KEY/)).toBeInTheDocument();
  });

  it('renders the table view alongside the charts', async () => {
    mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(SUMMARY) : jsonResponse(SERIES),
    );

    renderApp();

    await waitFor(() => expect(screen.getByTestId('series-table')).toBeInTheDocument());
    // Required by the contrast relief rule, not optional - see SeriesTable.
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('states that the saving is a calculation, not an invoice', async () => {
    mockFetch((url) =>
      url.includes('/summary') ? jsonResponse(SUMMARY) : jsonResponse(SERIES),
    );

    renderApp();

    await waitFor(() => expect(screen.getByText('70.9%')).toBeInTheDocument());
    // The same discipline as scaling-model.md: a derived figure says so, so
    // nobody quotes it as a measurement.
    expect(screen.getByText(/CALCULATION/)).toBeInTheDocument();
  });
});
