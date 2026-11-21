import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { SignIn } from './SignIn';

/**
 * The screen that turned the dashboard from a local tool into something
 * hostable.
 *
 * The assertions are about the credential, not the layout: that the key leaves
 * as a bearer token exactly once, that a rejection is reported in words someone
 * can act on, and that nothing keeps the key afterwards.
 */
describe('SignIn', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function mockFetch(impl: (url: string, init?: RequestInit) => Response) {
    const spy = vi.fn((url: unknown, init?: unknown) =>
      Promise.resolve(impl(String(url), init as RequestInit)),
    );
    vi.stubGlobal('fetch', spy);
    return spy;
  }

  it('exchanges the key for a session and reports the tenant', async () => {
    const fetchSpy = mockFetch(
      () =>
        new Response(JSON.stringify({ tenant_id: 'acme' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    const onSignedIn = vi.fn();

    render(<SignIn onSignedIn={onSignedIn} />);
    await userEvent.type(screen.getByLabelText(/api key/i), 'sk-acme-123');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('acme'));

    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/v1/dashboard/session');
    expect(init.method).toBe('POST');
    // The key travels as a bearer token on one request and nowhere else.
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer sk-acme-123');
    // Without this the Set-Cookie is dropped and the whole flow silently fails
    // on the NEXT request rather than this one.
    expect(init.credentials).toBe('same-origin');
  });

  it('says the key was rejected rather than printing a status code', async () => {
    mockFetch(() => new Response('', { status: 401 }));
    const onSignedIn = vi.fn();

    render(<SignIn onSignedIn={onSignedIn} />);
    await userEvent.type(screen.getByLabelText(/api key/i), 'sk-wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/rejected that api key/i);
    // Still on the form, so the key can be corrected without a reload.
    expect(onSignedIn).not.toHaveBeenCalled();
  });

  it('does not submit an empty key', async () => {
    const fetchSpy = mockFetch(() => new Response('', { status: 200 }));

    render(<SignIn onSignedIn={vi.fn()} />);

    // Disabled rather than submitting and letting the gateway answer: a 401 for
    // an empty field reads as "your key is wrong" when nothing was typed.
    expect(screen.getByRole('button', { name: /sign in/i })).toBeDisabled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('clears the key from the field once accepted', async () => {
    mockFetch(
      () =>
        new Response(JSON.stringify({ tenant_id: 'acme' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    );

    render(<SignIn onSignedIn={vi.fn()} />);
    const input = screen.getByLabelText(/api key/i);
    await userEvent.type(input, 'sk-acme-123');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    // The cookie is the credential from here on. A key left in component state
    // is readable by anything that can reach the React tree.
    await waitFor(() => expect(input).toHaveValue(''));
  });

  it('masks the key as it is typed', () => {
    render(<SignIn onSignedIn={vi.fn()} />);

    // A screen share or a recording of a demo would otherwise capture it.
    expect(screen.getByLabelText(/api key/i)).toHaveAttribute('type', 'password');
  });
});
