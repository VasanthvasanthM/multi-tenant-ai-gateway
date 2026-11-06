# ADR-0011: MockLlmProvider is the default; Ollama is off

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

The gateway needs a default upstream for development, tests and load measurement. The
candidates are a real hosted provider, a local model through Ollama, or a mock.

## Decision

`MockLlmProvider` is the default. `OpenAiCompatibleProvider` is configured but
inactive without a key. Ollama support exists and is **off by default**.

## Rationale

**A hosted provider cannot be the default.** It requires a credential to run the test
suite, costs money per CI run, and introduces network variance into every latency
measurement. CI would fail for anyone without a key.

**Ollama would distort every benchmark.** There is no GPU on this machine, so
CPU inference runs at roughly 8-15 tokens/second. A 500-token response therefore takes
30-60 seconds. Every measurement would be dominated by that: the cache-hit path would
look infinitely fast by comparison, the circuit breaker would trip on timeouts
constantly, and the USL fit would measure Ollama's throughput rather than the
gateway's. The one number the project actually needs — how the *gateway* scales — would
be unobtainable.

**A mock makes the gateway measurable.** With a deterministic, configurable-latency
mock, a measured latency is the gateway's own. That is what makes the Tier-1 and Tier-2
figures in [../architecture/scaling-model.md](../architecture/scaling-model.md) mean
anything.

The mock also has slow and failing variants, which is how the circuit breaker was
verified: it opened after exactly 10 failures, rejected 18 further calls without
attempting them, and all 14 user-facing requests were served via fallback. Reproducing
that against a real provider would require deliberately breaking someone else's
service.

## Consequences

**Accepted costs**

- The default configuration does not produce real completions. Mitigated by making the
  `served_by` and `provider` fields explicit in every response, so a mock answer is
  never mistaken for a real one.
- Real-provider integration is exercised less often. Mitigated by
  `OpenAiCompatibleProvider` being contract-tested against the wire format.
- A reader could mistake a mock benchmark for a production one. Which is why this ADR
  exists and why the scaling document labels every number (M) or (C).

**Benefits**

- `mvn verify` needs no credentials and no network.
- Latency measurements isolate the gateway.
- Failure injection is trivial and repeatable.
- No cost to run CI.

## Enabling a real provider

```yaml
gateway.providers.primary: openai
```

plus the base URL and key by environment variable. The fallback chain then puts the
mock last, so a provider outage degrades to a mock answer rather than an error — which
is the right behaviour for a demonstration and the **wrong** behaviour for production,
where the mock should be removed from the chain entirely.
