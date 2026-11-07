import type { GatewayErrorBody } from './types';

export interface DisplayError {
  readonly title: string;
  readonly message: string;
  readonly retryable: boolean;
}

interface FetchBaseQueryError {
  readonly status: number | string;
  readonly data?: unknown;
}

function isFetchError(value: unknown): value is FetchBaseQueryError {
  return typeof value === 'object' && value !== null && 'status' in value;
}

function isGatewayBody(value: unknown): value is GatewayErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    'error' in value &&
    typeof (value as { error: unknown }).error === 'object'
  );
}

/**
 * Turns an RTK Query error into something worth showing a person.
 *
 * The gateway sends a structured body with a `retryable` flag on every error,
 * and this is where that flag earns its place: the UI offers retry only when
 * the server said retrying can work. Guessing from the status code is wrong in
 * both directions - 402 is a 4xx that never succeeds on retry, and 503 is a 5xx
 * that usually does.
 *
 * 401 gets its own message because the most likely cause is a missing or stale
 * VITE_API_KEY, and "Unauthorized" sends people looking at the gateway instead
 * of at their own .env.local.
 */
export function toDisplayError(error: unknown): DisplayError {
  if (!isFetchError(error)) {
    return {
      title: 'Something went wrong',
      message: 'The dashboard could not load. Check the browser console.',
      retryable: true,
    };
  }

  // FETCH_ERROR means the request never reached a server - the gateway is not
  // running, or the dev proxy is pointed somewhere wrong. Saying so is far more
  // useful than a generic failure, because the fix is in the reader's hands.
  if (error.status === 'FETCH_ERROR') {
    return {
      title: 'Cannot reach the gateway',
      message:
        'No response from the gateway. Is it running, and does VITE_GATEWAY_URL point at it?',
      retryable: true,
    };
  }

  if (error.status === 401) {
    return {
      title: 'Not authenticated',
      message:
        'The gateway rejected the API key. Set VITE_API_KEY in .env.local to a key the gateway knows.',
      retryable: false,
    };
  }

  if (isGatewayBody(error.data)) {
    const body = error.data.error;
    return {
      title: body.code === 'rate_limited' ? 'Rate limited' : 'Request failed',
      message: body.message,
      retryable: body.retryable,
    };
  }

  return {
    title: `Request failed (${String(error.status)})`,
    message: 'The gateway returned an error without a readable body.',
    // 5xx is worth retrying; a 4xx without a body is not.
    retryable: typeof error.status === 'number' && error.status >= 500,
  };
}
