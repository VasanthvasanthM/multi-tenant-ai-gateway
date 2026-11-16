import { useCallback, useState } from 'react';

import { startSession } from '../api/client';

export interface SignInProps {
  /** Called with the tenant id once the gateway has accepted the key. */
  readonly onSignedIn: (tenantId: string) => void;
}

/**
 * Exchanges an API key for a session cookie.
 *
 * ## Why this screen exists
 *
 * Until it did, the only way into the dashboard was `VITE_API_KEY`, which Vite
 * inlines into the bundle - so every visitor could read the tenant's API key out
 * of devtools, and the dashboard was a local-development tool that could not be
 * hosted. The gateway's session endpoints and this module's `startSession` were
 * both built and tested, and nothing called them: the secure path existed at
 * both ends and was never joined in the middle.
 *
 * ## The key is never stored
 *
 * It lives in component state for exactly as long as it takes to post it, and
 * the response is an `HttpOnly` cookie that script cannot read. Not localStorage,
 * not a module variable, not Redux - anywhere it were kept would be readable by
 * an XSS on this page, which is the whole thing the cookie exists to avoid.
 *
 * The tenant id that comes back IS kept, in `sessionStorage`, so a page reload
 * does not look signed-out. That is a display label, not a credential: holding it
 * grants nothing, because the cookie is what the gateway actually checks.
 */
export function SignIn({ onSignedIn }: SignInProps) {
  const [key, setKey] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const submit = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault();
      if (key.trim() === '' || busy) {
        return;
      }

      setBusy(true);
      setError(undefined);
      try {
        const tenantId = await startSession(key.trim());
        // Cleared before the parent re-renders, so the key does not sit in a
        // retained component state object afterwards.
        setKey('');
        onSignedIn(tenantId);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : 'Could not sign in.');
        setBusy(false);
      }
    },
    [key, busy, onSignedIn],
  );

  return (
    <main className="app">
      <form className="signin" onSubmit={submit} aria-label="Sign in">
        <h1>AI Gateway</h1>
        <p className="subtitle">Enter an API key to view this tenant&rsquo;s usage.</p>

        <label className="signin-label" htmlFor="api-key">
          API key
        </label>
        {/* type=password so the key is not shoulder-surfed or captured by a
            screen recording, and autoComplete=off so a browser does not offer
            to save a credential the page deliberately does not keep. */}
        <input
          id="api-key"
          className="signin-input"
          type="password"
          value={key}
          onChange={(event) => setKey(event.target.value)}
          placeholder="sk-..."
          autoComplete="off"
          spellCheck={false}
          disabled={busy}
        />

        {error !== undefined && (
          <p className="signin-error" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="signin-submit" disabled={busy || key.trim() === ''}>
          {busy ? 'Signing in...' : 'Sign in'}
        </button>

        <p className="signin-note">
          The key is exchanged for a session cookie and is never stored by this page.
        </p>
      </form>
    </main>
  );
}
