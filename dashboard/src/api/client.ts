/**
 * Where the credential comes from.
 *
 * ## The key is no longer compiled into the bundle
 *
 * It used to be: `import.meta.env.VITE_API_KEY` is inlined by Vite, so every
 * visitor could read the tenant's API key out of devtools. That made the
 * dashboard a local-development tool and nothing else.
 *
 * Now the key is exchanged once, by hand, for a session cookie:
 *
 *   POST /v1/dashboard/session   Authorization: Bearer <key>
 *     -> Set-Cookie: aigw_dashboard=<opaque>; HttpOnly; SameSite=Strict
 *
 * `HttpOnly` is the important part: script cannot read the cookie, so an XSS on
 * this page cannot exfiltrate a usable credential. The key is typed into a form,
 * sent once, and never stored - not in a bundle, not in localStorage, not in a
 * module variable.
 *
 * The cookie authenticates READ-ONLY usage endpoints only. It cannot reach
 * /v1/chat/completions, so a stolen cookie cannot spend money. That bound is
 * what makes SameSite=Strict sufficient without a CSRF token: there is no
 * state-changing request a forged cross-site call could make.
 *
 * `VITE_API_KEY` is still honoured when present, for a developer running against
 * their own gateway, and the app says so in the UI. It must not be set in a
 * hosted build.
 */
function apiKey(): string | undefined {
  return import.meta.env['VITE_API_KEY'];
}

/**
 * Base URL: the page's own origin.
 *
 * SAME-ORIGIN by design. In development that origin is the Vite dev server,
 * which proxies `/v1` to the gateway; in production it is whatever serves the
 * dashboard behind the same ingress as the gateway. Either way the browser
 * makes no cross-origin request, so the gateway needs no CORS policy - and the
 * usual shortcut, permissive CORS, means shipping a production service that
 * accepts credentialed requests from any origin in order to make a dev server
 * work.
 *
 * Resolved from `window.location.origin` rather than left as the empty string,
 * which would be equivalent in a browser and is NOT equivalent in Node:
 * `new Request('/v1/usage/summary')` throws "Failed to parse URL" outside a
 * browser, because only a browser resolves a relative URL against a location.
 * RTK Query builds a Request internally, so every integration test failed with
 * a TypeError that looked like a mocking problem and was an environment
 * difference.
 */
export const API_BASE: string =
  typeof window !== 'undefined' && window.location !== undefined
    ? window.location.origin
    : '';

/** Exchanges a key for a session cookie. Resolves to the tenant id. */
export async function startSession(apiKeyValue: string): Promise<string> {
  const response = await globalThis.fetch(`${API_BASE}/v1/dashboard/session`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKeyValue}` },
    // Required for the Set-Cookie to be honoured, and for the cookie to be sent
    // on subsequent requests. Same-origin by design, so this is not a CORS
    // credential grant.
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new Error(
      response.status === 401
        ? 'The gateway rejected that API key.'
        : `Could not start a session (HTTP ${response.status}).`,
    );
  }
  const body = (await response.json()) as { tenant_id?: string };
  return body.tenant_id ?? 'unknown';
}

/** Ends the session and clears the cookie. */
export async function endSession(): Promise<void> {
  await globalThis.fetch(`${API_BASE}/v1/dashboard/session`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });
}

export function authHeaders(): Record<string, string> {
  const key = apiKey();
  if (key === undefined || key === '') {
    return {};
  }
  return { Authorization: `Bearer ${key}` };
}

/** True when a key is configured at all, so the UI can say so rather than 401. */
export function hasApiKey(): boolean {
  const key = apiKey();
  return key !== undefined && key !== '';
}
