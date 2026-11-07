# Tenant dashboard

React 19 + TypeScript + Vite. Shows a tenant their own usage: how much of their
traffic the cache served, and what that saved.

```bash
cd dashboard
npm install
cp .env.example .env.local     # then set VITE_API_KEY
npm run dev                    # http://localhost:5173
```

Requires a gateway running on `http://localhost:8080` (or set
`VITE_GATEWAY_URL`).

| Script | What it does |
|---|---|
| `npm run dev` | Dev server, proxying `/v1` to the gateway |
| `npm run build` | Typecheck then production build |
| `npm test` | Vitest, 37 tests |
| `npm run lint` | ESLint, zero warnings allowed |
| `npm run typecheck` | `tsc --noEmit` |

## What it reads

Two endpoints, both documented in [`../docs/api/openapi.yaml`](../docs/api/openapi.yaml):

- `GET /v1/usage/summary?hours=` — the four headline figures
- `GET /v1/usage/series?hours=` — hourly points for the charts

**The tenant comes from the API key.** There is deliberately no `?tenant=`
parameter: it would make a cross-tenant read a URL away, and the mistake would
look like a feature.

## The API key is not safe here

`VITE_API_KEY` is **compiled into the bundle**. Anything in a `VITE_` variable
is visible in the devtools of every user who loads the page, so a bundle built
this way hands the tenant credential to anyone who opens it.

That is fine for a developer running against their own local gateway and for
nothing else. A hosted dashboard needs one of:

1. **A session cookie.** The dashboard authenticates a *user*, the server maps
   that session to a tenant, and the browser never holds an API key.
2. **A backend-for-frontend.** The dashboard calls its own origin and that
   server adds the key, which also lets it enforce which tenant a user may read.

Neither exists. The gateway authenticates an API key per request and has no
notion of a user session, so a hosted dashboard is a **gap**, not a
configuration change. Recorded in [`../PROGRESS.md`](../PROGRESS.md).

## Decisions worth knowing

**Same-origin by design.** `API_BASE` is `window.location.origin`, so the
browser never makes a cross-origin request and the gateway needs no CORS
policy. In development that origin is the Vite dev server, which proxies `/v1`
to the gateway. The shortcut people reach for instead — permissive CORS on the
gateway — means shipping a production service that accepts credentialed
requests from any origin, to make a dev server work.

**RTK Query, not `useEffect` + `fetch`.** Not for the caching, which two
endpoints barely need, but because it removes four states a hand-rolled fetch
hook gets wrong: a response arriving after unmount, two windows selected quickly
resolving out of order, a refetch blanking the screen, and an error replaced by
a stale success on the next poll.

**The charts are code-split.** Recharts pulls in a set of d3 modules and took
the initial bundle to 684 kB — for a page whose most important content is four
numbers. Lazy-loading them brings it to 314 kB (100 kB gzipped) and the tiles
paint without waiting for the chart runtime.

**Derived figures come from the server.** Hit ratio, tokens saved and the money
estimate are computed server-side. The arithmetic has edge cases — an empty
window divides by zero, a replayed event could make a saving negative — and a
second implementation in TypeScript is a second place to get them wrong.

**The money tile disappears rather than showing zero.** The server omits
`estimated_saving_usd` when no token price is configured. A self-hosted model
has no per-token cost, so a money figure there is *meaningless* rather than
merely unknown, and `$0.00` would be a confident lie.

**Retry is offered only when the server says it would help.** Every gateway
error carries a `retryable` flag, because inferring it from the status is wrong
in both directions: a 402 is a 4xx that never succeeds on retry, and a 503 is a
5xx that usually does.

## Design and accessibility

Chart colours are **validated, not chosen** — checked against each surface with
the palette validator:

| | Light | Dark |
|---|---|---|
| requests | `#2a78d6` | `#3987e5` |
| provider | `#eb6834` | `#d95926` |
| cache | `#1baf7a` | `#199e70` |

Both sets pass the lightness band, chroma floor, CVD separation and
normal-vision floor. Two consequences are load-bearing:

- **Colour tracks the entity, never the series order.** Cache hits are always
  aqua and provider traffic always orange, in every chart. A palette indexed by
  position repaints the surviving series whenever a filter removes one.
- **The table view is required, not optional.** In light mode the aqua series
  measures 2.74:1 against the surface, below the 3:1 floor, and the documented
  relief is a visible label or a table view. That is why `SeriesTable` exists —
  no figure is ever available only as a low-contrast mark. It also answers "what
  was the number at 14:00", which a line chart makes you estimate.

**No dual-axis chart anywhere.** The hit ratio gets its own plot rather than a
second axis on the traffic chart: a ratio and a count share no scale, and the
crossover point on a two-axis chart is an artefact of the axis ranges rather
than anything in the data.

**The ratio axis is fixed to 0–1.** Auto-scaling makes a chart ranging 0.68 to
0.71 look like a catastrophe, and the same chart next week look calm, with
nothing on screen saying the axis moved.

## Two environment traps, both found by tests

**`import.meta.env.VITE_*` is a compile-time substitution, not a runtime
lookup.** Vite replaces it with a literal when it transforms the module, so
`vi.stubEnv` cannot change it — every app test rendered the "no API key"
panel until the value was supplied at build time via
[`.env.test`](.env.test).

**A relative URL cannot construct a `Request` outside a browser.** `API_BASE`
was originally `''`, which works in a browser and throws
`Failed to parse URL from /v1/usage/summary` under jsdom, because only a browser
resolves a relative URL against a location. RTK Query builds a `Request`
internally, so every integration test failed with a `TypeError` that looked like
a mocking problem and was an environment difference.
