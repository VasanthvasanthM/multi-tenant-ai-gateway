# B6c — The APIs

Two sides: what we expose to customers, and what we call on their behalf.

---

## What we expose — five endpoints

```
POST    /v1/chat/completions        the product
GET     /v1/usage/summary           what have I used?
GET     /v1/usage/series            ...per hour, for the chart
POST    /v1/dashboard/session       sign in
DELETE  /v1/dashboard/session       sign out
```

Operational, not for customers:

```
GET     /actuator/health            Kubernetes probes
GET     /actuator/prometheus        metrics scraping
GET     /actuator/metrics
GET     /actuator/info
```

### What each one is for

**`POST /v1/chat/completions`** - Acme's app asks the AI a question and gets an
answer. This is the endpoint a customer's code calls thousands of times a day.
**Everything else in the system exists to make this one fast and cheap.**

**`GET /v1/usage/summary`** - the big numbers at the top of the dashboard:
requests today, tokens used, cache hit ratio. One call, one row.

**`GET /v1/usage/series`** - the same numbers broken down by hour, for the graph
underneath.

**`POST` / `DELETE /v1/dashboard/session`** - a person signs in and out of the
dashboard.

**`/actuator/health`** - how a broken pod gets restarted automatically.
**`/actuator/prometheus`** - where every Grafana panel gets its data.

### Why two usage endpoints for the same data

```
summary  ->  1 row     "how much today?"
series   ->  720 rows  "how much each hour, for 30 days?"
```

One endpoint serving both would mean sending **720 rows to display a single
number** at the top of the page.

---

## The path is OpenAI's, deliberately

```java
POST("/v1/chat/completions")
```

Not a coincidence - it is OpenAI's exact endpoint with OpenAI's exact request and
response shape. A customer migrating changes **one line**:

```python
client = OpenAI(base_url="https://your-gateway/v1")
```

No new SDK, no code changes. Their existing library, retry logic and error
handling keep working.

> **Adopting an API shape you did not design is a product decision, not a
> technical one.** It removes the single biggest reason a customer says no.

It is also why `Retry-After` had to be correct, and why `tenant_migrating` is a
503 rather than something invented: **their SDK already knows how to handle
these.** An error shape a client does not recognise is an outage on their side.

---

## The dashboard session — why a cookie, not the API key

The dashboard runs in a browser. The obvious approach is to put the API key in
JavaScript and send it as a header. **Do not.** Any XSS, any browser extension,
any pasted console snippet, and the key leaks - and an API key does not expire.

```
POST /v1/dashboard/session     send the key once -> get a session cookie
```

```java
.httpOnly(true)
.secure(isHttps(request))
.sameSite("Strict")
.path("/v1")
.maxAge(DashboardSessions.TTL)
```

| setting | why |
|---|---|
| `httpOnly` | script cannot read it, so an XSS cannot steal a usable credential |
| `SameSite=Strict` | another site cannot make the browser send it - CSRF closed |
| `secure` **computed** | *"hard-coding it true would make the cookie silently undeliverable over plain HTTP, so local development would appear to log in and then fail every subsequent request with no clue why"* |
| `Path=/v1` | *"so it is not attached to requests for the dashboard's own static assets"* |

And a session **expires** after 8 hours; the API key does not. That is the whole
trade: a short-lived, narrow-scoped credential in the risky place.

The cookie also reaches **read-only** endpoints only. It cannot call
`/v1/chat/completions`, so a stolen cookie cannot spend money - which is what
makes `SameSite=Strict` sufficient without a CSRF token: there is no
state-changing request a forged cross-site call could make.

> **This flow existed on the gateway and in the dashboard's API client for weeks
> with nothing calling it** - no login form existed, so the dashboard still
> depended on a build-time key compiled into the bundle. Found and built
> 2026-11-16; see [`problems-and-solutions.md`](../problems-and-solutions.md).

---

## What we call

```
OpenAI-compatible    POST /v1/chat/completions
Ollama               POST /api/chat
                     GET  /api/tags        <- health check
```

### Why "OpenAI-compatible" rather than "OpenAI"

One provider class serves OpenAI, Together, Groq, Fireworks, Anyscale, vLLM and
anything else that copied the shape - which is most of the industry. **Same
code, different base URL and key.** Adding such a vendor is configuration, not a
new class.

### Ollama is the odd one on purpose

> *"Its `/api/chat` endpoint takes a different body and - importantly - streams
> differently."*

It exists to prove the abstraction is real. If every provider spoke the same
dialect, `LlmProvider` would be an interface with one implementation wearing
different hats, and you would discover that the day a genuinely different vendor
arrived. It also gives a **free local provider**: no API key, no network, no
bill.

### The health check is `GET /api/tags`

Ollama has no `/health`, so the provider asks it to list its models - a cheap
call that only succeeds if the service is genuinely up and answering.

> **Pick a health check that exercises the thing you depend on**, not one that
> proves a port is open.

---

## The whole surface

```
customer                           gateway                        vendor
--------                           -------                        ------
POST /v1/chat/completions   ->   [the only hot endpoint]   ->   POST /v1/chat/completions
                                                                POST /api/chat

GET  /v1/usage/summary      ->   reads the rollup table
GET  /v1/usage/series       ->   reads the rollup table
POST /v1/dashboard/session  ->   issues a cookie
```

**One endpoint carries the entire product.** Three let a human see what it did.
Two are sign-in and sign-out.

And note what is absent: no admin API, no tenant CRUD, no key management over
HTTP. The control plane has **no HTTP surface at all**, which is why
`TenantQuotaRegistry` currently resolves every tenant to the default - an honest
gap recorded in [B6c0](B6c0-what-each-service-does.md).

---

**Previous:** [B6b1 — each pattern, where and why](B6b1-patterns-where-and-why.md).
**Next:** [B7 — Redis](B7-redis.md).
