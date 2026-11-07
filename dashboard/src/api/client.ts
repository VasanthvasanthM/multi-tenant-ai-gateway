/**
 * Where the API key comes from.
 *
 * ## This is a development arrangement, and it is not safe for production
 *
 * `import.meta.env.VITE_API_KEY` is compiled INTO THE BUNDLE. It is visible in
 * devtools to every user of the page, so a bundle built this way hands the
 * tenant credential to anyone who loads it. That is acceptable for a developer
 * running against their own local gateway and for nothing else.
 *
 * A real deployment has two options, and both keep the key off the client:
 *
 *   1. A session cookie. The dashboard authenticates a USER (not a tenant), the
 *      server maps that session to a tenant, and the browser never holds an API
 *      key. This is the right answer for a hosted dashboard.
 *   2. A backend-for-frontend. The dashboard calls its own origin, and that
 *      server adds the API key. Also keeps the key server-side, and lets the
 *      BFF enforce which tenant a user may read.
 *
 * Neither is implemented. The gateway authenticates an API key per request and
 * has no notion of a user session, so a hosted dashboard is a gap rather than a
 * configuration change - recorded in PROGRESS.md.
 */

/**
 * Read on every call rather than captured at module load.
 *
 * At runtime it makes no difference - Vite inlines the value at build time, so
 * it cannot change while the page is open. It matters for TESTABILITY: a
 * module-scope `const` is evaluated the first time the module is imported,
 * which is before any test has run, so no test could ever exercise the
 * no-key-configured path. A function that reads it each time can be stubbed.
 *
 * That is a real tradeoff and not a free one: a reader could reasonably expect
 * a constant here. The alternative was mocking this module in every test that
 * touches the app, which hides the thing being configured behind a mock.
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
