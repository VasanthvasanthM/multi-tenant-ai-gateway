import { describe, expect, it } from 'vitest';

import { toDisplayError } from './errors';

describe('toDisplayError', () => {
  it('uses the retryable flag the server sent, not the status code', () => {
    // The whole reason the gateway sends the flag. Inferring from the status is
    // wrong in both directions: a 402 is a 4xx that never succeeds on retry,
    // and a 503 is a 5xx that usually does.
    const budget = toDisplayError({
      status: 402,
      data: {
        error: {
          message: 'token budget exhausted',
          type: 'invalid_request_error',
          code: 'budget_exhausted',
          retryable: false,
        },
      },
    });
    expect(budget.retryable).toBe(false);
    expect(budget.message).toContain('budget');

    const unavailable = toDisplayError({
      status: 503,
      data: {
        error: {
          message: 'circuit open for provider',
          type: 'server_error',
          code: 'circuit_open',
          retryable: true,
        },
      },
    });
    expect(unavailable.retryable).toBe(true);
  });

  it('names rate limiting specifically, because the action differs', () => {
    const limited = toDisplayError({
      status: 429,
      data: {
        error: {
          message: 'rate limit exceeded; retry after 3s',
          type: 'rate_limit_error',
          code: 'rate_limited',
          retryable: true,
        },
      },
    });
    expect(limited.title).toBe('Rate limited');
    expect(limited.retryable).toBe(true);
  });

  it('tells the reader where to look when the gateway is unreachable', () => {
    // FETCH_ERROR means the request never reached a server. Saying so is far
    // more useful than a generic failure, because the fix is in the reader's
    // hands rather than the gateway's.
    const unreachable = toDisplayError({ status: 'FETCH_ERROR', error: 'TypeError' });
    expect(unreachable.title).toContain('Cannot reach');
    expect(unreachable.message).toContain('VITE_GATEWAY_URL');
    expect(unreachable.retryable).toBe(true);
  });

  it('points a 401 at the key, not at the gateway', () => {
    const unauthorised = toDisplayError({ status: 401, data: { error: {} } });
    expect(unauthorised.message).toContain('VITE_API_KEY');
    // Not retryable: resending the same rejected key cannot work.
    expect(unauthorised.retryable).toBe(false);
  });

  it('handles an error with no readable body', () => {
    const noBody = toDisplayError({ status: 500, data: 'Internal Server Error' });
    expect(noBody.retryable).toBe(true);

    const clientNoBody = toDisplayError({ status: 418, data: undefined });
    expect(clientNoBody.retryable).toBe(false);
  });

  it('handles something that is not a fetch error at all', () => {
    expect(toDisplayError(new Error('boom')).retryable).toBe(true);
    expect(toDisplayError(undefined).title).toBe('Something went wrong');
  });
});
