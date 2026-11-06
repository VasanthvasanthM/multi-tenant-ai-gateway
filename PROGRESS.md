# PROGRESS — session handover file

**Purpose:** if the session is lost, read this file top to bottom and you have everything
needed to continue without re-deriving decisions.

**Last updated:** 2026-11-06 (session 1, through P13 CI/CD)
**Repo:** `C:\Users\vasan\OneDrive\Desktop\claudeProjects\ai-gateway`
**Git:** initialised, branch `main`, **no commits yet**

---

## 1. How to resume in one minute

```bash
cd "C:/Users/vasan/OneDrive/Desktop/claudeProjects/ai-gateway"
./mvnw -B -ntp validate       # should be BUILD SUCCESS across 9 modules
cat PROGRESS.md               # this file
cat README.md                 # architecture + stack
ls docs/adr/                  # why each decision was made
```

Then pick up at the first unchecked box in section 5.

---

## 2. What this project is

A multi-tenant AI gateway with a two-tier semantic cache (Redis exact-hash → pgvector cosine),
built on 100% free open-source software, designed so nothing in it blocks billion-user scale,
and able to *prove* that from a 4-core laptop.

Source requirement: `AI Gateway Architecture Blueprint (Free Open-Source Stack).pdf`
(the user's document). Five deliberate deviations from it — see section 4.

---

## 3. Environment facts (discovered, do not re-derive)

| Fact | Value |
|---|---|
| Machine | AMD Ryzen 5 7520U, **4 cores / 8 threads, 7.2 GB RAM**, 149 GB free |
| OS / shell | Windows 11, Git Bash available, PowerShell available |
| Java | OpenJDK 21.0.12.1 ✅ |
| Maven | via wrapper, 3.9.16 (no local install) |
| Docker | 28.1.1 ✅ running. ~11 GB reclaimable images |
| Already installed | helm v4.1.4, kubectl v1.33.0, terraform v1.15.3 |
| **Not** installed | k3d, k6, sonar-scanner (scanner not needed — Maven plugin) |
| Node / Python | Node 24.16.0, Python 3.13.2 |

### Gotchas already hit and solved — do not repeat

1. **Java's `HttpURLConnection` cannot reach GitHub release assets on this network**
   (connect timeout), but `curl` can. The Gradle wrapper failed because of this. If any
   tool needs a GitHub-hosted binary, fetch it with `curl` and place it manually.
2. **`mvnw` fetched from `raw.githubusercontent.com` is an unsubstituted template**
   (contains `@@project.version@@`). The working wrapper came from the Maven Central artifact
   `maven-wrapper-distribution-3.3.4-only-script.zip`.
3. **Windows Python cannot read Git-Bash `/c/...` paths.** Pass `C:/...` style paths.
4. **Gradle 9 + `info.solidsoft.pitest` 1.15.0 is broken** (`reporting.baseDir` removed).
   Irrelevant now — the build is Maven — but do not "helpfully" go back to Gradle.
5. **Heredocs in Bash truncate on apostrophes.** Use the Write tool for prose-heavy files.
6. **springdoc MUST stay on the 2.x line.** springdoc 3.x targets Spring Boot 4 and pulls
   Boot 4 modular artifacts (`spring-boot-jackson`, `spring-boot-webflux`, ...) alongside
   Boot 3.5's monolithic `spring-boot-autoconfigure`. Result: two `JacksonAutoConfiguration`
   classes and a `BeanDefinitionOverrideException` at startup. Pinned to 2.10.0.
7. **`mvn install` without `clean` can leave a stale fat jar.** When only a parent POM
   changes, the gateway's own sources are unchanged so `repackage` does not re-run and the
   old BOOT-INF/lib is kept. Always `clean` before inspecting a shaded jar.
8. **Port 8080 is occupied on this machine** by something else. Run the gateway with
   `SERVER_PORT=8099`.
9. **`bc` does not exist in Git Bash.** Use `awk` or `python` for shell arithmetic.
10. **Docker Desktop's engine stops on its own.** Restart with
    `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`, then wait ~40s.
11. **Jackson serialises `isX()` on records.** A helper named `isReadable()` silently added
    `"readable":true` to every cached value. Annotate derived getters `@JsonIgnore`.
12. **Port 5432 is ALSO taken by a native Postgres install** (like 8080). Run the
    pgvector container on host port **55432**.
13. **JVM timezone breaks Postgres connections.** Java reports legacy ids such as
    `Asia/Calcutta`; the JDBC driver sends that as a session parameter and slim container
    images only ship the modern `Asia/Kolkata`, so the connection is rejected with
    `invalid value for parameter "TimeZone"`. Every service runs with
    `-Duser.timezone=UTC`, set in the Paketo env in each pom.
14. **`::vector` casts collide with Spring's named parameters.** Spring reads `:` as the
    start of a parameter name, so `:query::vector` becomes a parameter called "vector" and
    the SQL is mangled before Postgres sees it. Use `CAST(:query AS vector)`.
15. **DJL needs `includeTokenTypes=true` for BERT ONNX models.** Without it ONNX Runtime
    rejects inference with `Input mismatch, looking for: [input_ids, attention_mask,
    token_type_ids]` - and only at first inference, which is why `load()` runs a probe.
16. **float32 tolerances.** Normalising and dot-producting in float32 accumulates ~3.4e-8
    of error. Asserting similarity to 1e-9 is impossible, not strict. Use 1e-6.
17. **Flyway `baseline-on-migrate` silently skips V1.** Several services share one
    database. The second service to start finds a non-empty schema, baselines it at
    Flyway's DEFAULT baseline version of **1**, and therefore treats `V1__<service>.sql`
    as already applied. No error, no warning - the app starts healthy and its tables do
    not exist. **Always set `baseline-version: 0`.**
18. **Git Bash mangles container paths.** `docker exec ... /opt/kafka/bin/x.sh` becomes
    `C:/Program Files/Git/opt/kafka/...`. Prefix with `MSYS_NO_PATHCONV=1`.
19. **Kafka offset reset needs an Empty consumer group.** Stopping the process is not
    enough; Kafka holds the member until `session.timeout.ms` (45 s) expires. Poll
    `--describe --state` until it reports `Empty`, or the reset is silently rejected.
20. **The JaCoCo quality gate was decorative for most of this build.** Two separate
    reasons, both silent:
    (a) `jacoco:check` reads the module's own `target/jacoco.exec`; `report-aggregate`
        writes a report but no exec file, so check logged *"Skipping JaCoCo execution due
        to missing execution data file"* at INFO and passed. Fixed by adding a `merge`
        execution before it.
    (b) Even merged, `check` with `element=BUNDLE` analyses the **current project's**
        classes - and `coverage-report` is `packaging=pom` with none, so the rule was
        satisfied by an empty bundle. Enforcement now lives **per module** in the
        `quality` profile, where check has classes to measure.
    A gate that always passes is worse than no gate, because it is believed.
21. **Spotless needs `lineEndings=UNIX` plus `.gitattributes`.** Otherwise CRLF/LF
    differences make the format check pass or fail depending on which OS ran it.
    `sortPom` was also removed - it reindents and reorders POMs, detaching the comments
    that explain why each version is pinned.
22. **Python's `write_text` on Windows writes CRLF.** With no `newline=` argument it
    translates every `
` to `os.linesep`, so any file patched with a Python script picks
    up CRLF and then fails the Spotless check (which is pinned to LF). Either pass
    `newline="
"` or run `./mvnw spotless:apply` afterwards.
23. **A PMD `<exclude>` in the wrong category block is a SILENT no-op.** It only applies
    inside the `<rule ref="category/...">` that actually defines that rule; PMD accepts a
    misplaced one without complaint and the rule keeps firing. Cost three build cycles:
    `AvoidFieldNameMatchingMethodName` is in errorprone (not codestyle), `UseUtilityClass`
    and `AvoidCatchingGenericException` are in design (not bestpractices/errorprone).
    Verify the violation count actually drops after adding one.
24. **Spring Boot 3.3+ changed the layered-extraction format.** The old
    `-Djarmode=layertools extract` wrote a `BOOT-INF` tree started via
    `org.springframework.boot.loader.launch.JarLauncher`. The new
    `-Djarmode=tools extract --layers` writes a thin `app.jar` plus a sibling `lib/`,
    started with a plain `java -jar app.jar`. Using JarLauncher against the new layout
    fails at **container start**, not build time, with
    `Could not find or load main class ...JarLauncher`.
25. **A nested jar repacked into a Boot fat jar must be STORED, not deflated.** The
    loader maps nested jars directly out of the outer archive. Use `zip -0`, or startup
    fails with `Unable to open nested entry`.
26. **`--network host` does not expose container ports on Docker Desktop for Windows.**
    Outbound works, inbound does not. Use `-p` and `host.docker.internal`.
27. **Windows loses the executable bit, and it breaks every Linux CI job at once.**
    `core.fileMode=false` on Windows, so git records `mvnw` as 100644 and the runner
    fails with Permission denied in build, integration AND static analysis
    simultaneously - which reads as a broken build, not a file mode. Fix with
    `git update-index --chmod=+x mvnw`. CI now checks for it explicitly.
28. **A GitHub Action version that does not resolve fails at "Set up job".** No step
    name appears in the log, so the cause is only visible in the check-run annotation
    (`/check-runs/<id>/annotations`). Verify a tag exists before pinning it:
    `curl -o /dev/null -w '%{http_code}' https://api.github.com/repos/<action>/git/ref/tags/<tag>`
29. **Static initialiser order bit us once.** `ProviderId.CACHE` was declared above the
    `VALID` pattern its constructor reads, so the class failed to initialise. Declare
    patterns and other constructor-visible statics FIRST. Pinned by `ValueTypeConstantsTest`.
30. **Container repository names must be lowercase, and `github.repository_owner` is not.**
    It preserves the account capitalisation (`VasanthvasanthM`), and the OCI distribution
    spec allows only `[a-z0-9._-]`, so buildx fails with `repository name must be
    lowercase`. GitHub expressions have **no lowercase function** - compute it in a shell
    step (`tr '[:upper:]' '[:lower:]'`) and reuse the result. This failed all five image
    jobs at once and, like gotcha 28, the message appeared only in the check annotation.
31. **`if: always()` on a SARIF upload turns one failure into two.** When the build fails
    Trivy never writes the file, so the upload fails with `Path does not exist` - and that
    is the error you read first, and it explains nothing. Guard it:
    `if: always() && hashFiles(format('trivy-{0}.sarif', matrix.service)) != ''`
32. **Scanning one build and pushing another is not a vulnerability gate.** The images job
    used to call `build-push-action` three times (build+load, push GHCR, push Docker Hub).
    Each re-sent the context and re-ran the build, so Trivy approved one image while a
    later build produced the published bytes. Build ONCE with `load: true` and every tag,
    then `docker push` each tag - the scanned digest and the published digest are then the
    same object. Also roughly a third of the previous build time.
33. **Piping into `while read` hides push failures.** A pipe runs the loop in a subshell,
    and a plain `run:` block is `bash -e` **without** `pipefail`, so a failure on any
    iteration but the last leaves the step green. Redirect from a file instead. Related:
    `printf '%s'` leaves the final line unterminated and `read` then returns false for it,
    silently skipping the LAST item - use `echo`.
34. **The Docker build context was 643 MB with no `.dockerignore`.** The Dockerfile reads
    exactly one jar and one script. Now 231 MB. The size was the smaller problem: anything
    in the context can be `COPY`d, so `.env` files, `*.pem` and kubeconfigs were being
    handed to the daemon on every build. **`target/` must NOT be excluded** - the image is
    built from an already-packaged jar, and excluding it breaks the COPY with
    `file not found`, which reads as a Dockerfile bug rather than a context one.
35. **MetalLB rejects its own CRs for a few seconds after install.** The validating webhook
    starts serving slightly after the Deployment reports Available, so applying the
    `IPAddressPool` immediately fails with `connection refused` about one run in three.
    Retry in a loop; do not sleep a guessed interval. `scripts/cluster-up.sh` retries 30x.
36. **k3s ships its own LoadBalancer provider and its own ingress, and both fight ours.**
    Create the cluster with `--disable=servicelb` (klipper would race MetalLB for every
    LoadBalancer Service, flipping the external IP depending on which reconciled last) and
    `--disable=traefik` (two controllers watching the same Ingress objects). Leave
    metrics-server ENABLED - the HPA CPU fallback and `kubectl top` both need it.
37. **A MetalLB address is not routable from a Windows or macOS host.** There is no route
    from the host into the Docker bridge network, so the external IP works only from inside
    the cluster. A LoadBalancer Service also allocates a NodePort: pin it (30080/30443) and
    map a k3d host port onto it. MetalLB still does the real L4 allocation, and the host
    gets a path that works - `curl -H 'Host: ai-gateway.local' http://localhost:8080/...`.
38. **A 1-minute `rate()` needs a scrape interval of 30 s or less.** The prometheus-adapter
    rule computes `rate(...[1m])`, which needs two samples inside the window. At 60 s it
    returns nothing, the HPA reports `<unknown>`, and **falls back to CPU without
    erroring** - so an I/O-bound gateway shows a flat replica count that looks like
    evidence it does not need to scale. Always check `kubectl get hpa` shows a number
    before trusting a load run.
39. **A PodDisruptionBudget of `minAvailable: 1` on a single replica blocks every voluntary
    eviction**, `kubectl drain` and node upgrades included. The chart gates the PDB on
    effective replicas > 1, and `values-dev.yaml` disables it outright.
40. **Heredocs in this environment truncate on apostrophes.** `cat > file <<'EOF'` with
    `k3s's` in the body dies with `unexpected EOF while looking for matching quote` and
    writes nothing. Write such files with the Write tool or a script file instead.
41. **`?` is a YAML indicator inside a flow mapping.** `- { role: user, content: How do
    I reset my password? }` fails to parse with `expected ',' or '}', but got '?'`. Quote
    any flow-mapping scalar containing `?`, `:` or `#`. Block style does not care, which
    is why it only bites in the compact examples.
42. **A spec can document something the server never sends.** An OpenAPI draft here
    declared an `X-Gateway-Served-By` response header that does not exist anywhere in the
    code. Nothing catches that by reading - so `scripts/check-openapi.py` now asserts the
    spec's error-code enum equals the set of `code()` values `GatewayError` actually
    returns, and that every status it can produce has a documented response. CI runs it.
    Verified to work by deleting a code from the spec and watching it fail.
43. **A Grafana panel querying a metric that does not exist renders EMPTY, with no
    error.** It is indistinguishable from "no traffic yet", so a broken dashboard sits
    broken indefinitely. `scripts/check-dashboards.py` asserts every metric in every
    panel is a meter the Java actually registers (28 meters -> 67 Prometheus series
    names), plus unique panel ids, no grid overlaps and no dual-axis panel. In CI.
44. **`serviceMonitorSelectorNilUsesHelmValues` is the kube-prometheus-stack setting
    people lose a day to.** By default the operator selects only ServiceMonitors carrying
    its own release label, so the application's ServiceMonitor is ignored, the target is
    simply ABSENT from the Targets page, and there is no error anywhere. Set it - and its
    pod/rule/probe siblings - to `false`.
45. **The Loki chart's defaults do not fit a laptop, and the overage is in the caches.**
    `chunksCache` and `resultsCache` deploy memcached StatefulSets whose combined
    requests run to several gigabytes; most pods then sit Pending forever, which reads as
    a broken chart. Set `deploymentMode: SingleBinary`, `replication_factor: 1` (the
    default 3 leaves the ring under-replicated and Loki refuses writes), and disable both
    caches plus `lokiCanary`, `test` and `gateway`.
46. **A trace id must never become a Loki label.** Labels define streams, so an unbounded
    label creates one stream per request and destroys the index. Alloy puts `trace_id`
    in **structured metadata** instead, and the Grafana `derivedFields` matcher reads it
    from there.
47. **Tracing was fully configured and completely inert.** `micrometer-tracing-bridge-otel`
    and `opentelemetry-exporter-otlp` were on the classpath and `application.yml` set a
    10% sampling probability - but no OTLP endpoint was ever configured, so spans had
    nowhere to go. Nothing reports this, because having no collector is not an error. The
    chart now emits `MANAGEMENT_OTLP_TRACING_ENDPOINT`, off by default so that pointing
    at a Tempo that is not installed does not log a connection failure on every flush.
48. **Scrape interval is a correctness setting, not a tuning preference.** The
    prometheus-adapter rules use `rate(...[1m])`, which needs two samples in the window.
    Above 30 s the custom metric returns nothing, the HPA reports `<unknown>` and **falls
    back to CPU without erroring** - and an I/O-bound gateway on a CPU target produces a
    flat replica count that looks like evidence it does not need to scale. Always check
    `kubectl get hpa` shows a number before trusting a run.
49. **`http_server_requests_seconds_count` includes the actuator probes.** Readiness every
    5 s and liveness every 10 s give each pod a request-rate floor of ~0.3 rps that never
    reaches zero, so the HPA never sees idle and a scale-down test is unreadable. It can
    be filtered with `uri!~"/actuator.*"`, and that filter is one typo from silently
    including probes again. The adapter now uses the gateway's **own**
    `gateway_request_duration_seconds_count`, which cannot have the problem: actuator
    requests never enter `CompletionPipeline`.
50. **A slice with its end boundary before its start DUPLICATES text instead of failing.**
    Editing this file by `s[:start] + s[end:]` to move the P10 block used the P11 heading
    as the end marker - but the outstanding P10 block sits AFTER the amber P11 block, so
    `end < start` and everything between them was copied rather than removed. Assert
    `end > start` whenever a boundary is found by searching for a sibling heading.

    **It then bit again in the other direction.** Replacing the P11 block used the P7
    heading as the end marker; `end > start` held, but the P13 block sat BETWEEN them
    and was silently deleted. Asserting the boundary order is not enough - the range
    has to be checked for unrelated siblings, or the result verified afterwards. The
    cheap check that caught both: list every heading and assert all 15 phases are
    still present.
51. **`jacoco:report` is bound to `verify`, not `test`.** So `mvn test` updates
    `jacoco.exec` but leaves `jacoco.xml` stale, and coverage appears completely
    unchanged after adding a hundred tests. Run `mvn jacoco:report` explicitly, or check
    the two file timestamps before believing a number.
52. **A `test`-phase coverage figure is NOT comparable to a `verify`-phase one in this
    repo.** `audit`, `metering` and `indexer` get their coverage almost entirely from
    Testcontainers `*IT` tests, which only run under `verify`. Unit tests alone put them
    at 0-21%; with ITs, `audit` and `metering` are at 100%. Always say which phase a
    number came from, or two honest measurements look like a regression.
53. **A counter placed after `flatMap` counts the DECISION, not the event.** Both cache
    tiers had this: the miss counter sat after the deserialise/decide step, so it also
    fired for every empty result that step had already counted. A stale entry scored two
    misses; a below-threshold candidate scored below_threshold AND miss. The hit ratio is
    hits over the sum of all outcomes, so it silently deflated the product's headline
    number. Put the counter where it observes the RAW result, and make the outcomes
    mutually exclusive and exhaustive - one lookup, exactly one counter.
54. **A bare `new ObjectMapper()` writes an `Instant` as a floating-point epoch**
    (`1.7939664E9`). Spring Boot disables `WRITE_DATES_AS_TIMESTAMPS`; Jackson does not.
    A test using its own mapper therefore asserts a wire format no deployment produces -
    and keeps passing when the real one changes. Build test mappers with
    `JsonMapper.builder().addModule(new JavaTimeModule()).disable(WRITE_DATES_AS_TIMESTAMPS)`.
55. **Assert the WIRE field names, not the Java component names.** `GatewayEvents` uses
    short `@JsonProperty` names (`rid`, `tid`, `pt`, `at`) because an event is written
    once per request. A test asserting `requestId` reads an absent field, gets `""`, and
    would pass unchanged after a rename that broke every consumer.
56. **`GatewayError.InvalidRequest` requires a non-null message, and
    `IllegalArgumentException.getMessage()` is nullable.** The error handler passed one
    straight to the other, so `throw new IllegalArgumentException()` with no text raised
    an NPE from inside the handler of last resort - and the client got an unstructured
    framework error rather than the documented 400.
57. **`-Dtest` takes a COMMA-separated list, not `+`.** `-Dtest='A+B'` fails with
    "No tests matching pattern", which reads as a missing class rather than as syntax.
58. **`-pl <module>` alone resolves siblings from `~/.m2`, not from the working tree.**
    Adding a class to `common` and testing `gateway` with `-pl gateway` fails with
    "class file for ...ShardRouter not found", which reads as a broken import rather
    than a stale installed jar. Use `-pl gateway -am`.
59. **`getAndIncrement % size` breaks after ~2 billion calls.** An AtomicInteger wraps to
    `Integer.MIN_VALUE`, and `%` then yields a NEGATIVE index and an
    ArrayIndexOutOfBoundsException. At a few thousand requests a second that is about a
    fortnight - long enough that it only ever happens in production. Use
    `Math.floorMod`.
60. **PMD's `AvoidInstantiatingObjectsInLoops` fires on startup-time construction.**
    Building one client set per configured shard is exactly the required number of
    objects, but the rule cannot tell. A `Collectors.toMap` reads just as well and is
    cheaper than carrying a documented exclusion - prefer restructuring to excluding.

### RAM budget — the hard constraint

The full stack does not fit in 7.2 GB. Profiles are mandatory; **never run two at once**.

| Profile | Contents | ~RAM |
|---|---|---|
| `dev` | Compose: 1 gateway + Redis + Postgres | 1.1 GB |
| `scale` | k3d: 1→6 gateway pods, Redis, Postgres, mock provider | 2.8 GB |
| `full` | k3d: all 5 services + Redis + Postgres + Kafka | 2.6 GB |
| `obs` | k3d: 1 gateway + Prometheus/Grafana/Loki/Tempo | 2.9 GB |
| `quality` | SonarQube alone, stack stopped | 2.5 GB |

---

## 4. Decisions already made (do not relitigate)

### User instructions given explicitly
- **No Kotlin.** Build is Maven/XML. (Was Gradle Kotlin DSL; converted.)
- **Use Kubernetes + Helm + Terraform.**
- **Use a free load balancer** → MetalLB (L4) + ingress-nginx (L7).
- **Microservices, not a monolith** → 5 services + 2 libraries.
- **Outbox + Saga + read replication + consistent-hash sharding** all required.
- **CI/CD on both GitHub Actions and Jenkins.**
- **Goal: scale to billions of users**, proven from this laptop.
- **Do not accept credentials in chat.** User runs `gh auth login` / `docker login` locally.

### Architectural decisions
| # | Decision | Reason |
|---|---|---|
| 1 | Java 21 + Spring WebFlux | I/O-bound workload; Reactor gives **backpressure**, which virtual threads do not |
| 2 | Embeddings **in-process** (ONNX + DJL, all-MiniLM-L6-v2, 384-d) | A remote embedding API costs 50–300 ms against a 15 ms budget |
| 3 | Kafka **beside** the request path, never inside | Broker hop breaks SSE and adds latency; blueprint had it inline |
| 4 | **No PgBouncer** | Conflicts with r2dbc-pool; breaks server-side prepared statements |
| 5 | **Token bucket**, not leaky bucket | Real API clients burst |
| 6 | Shard key = `tenantId`, consistent hash + ~150 vnodes | Cache lookups are always tenant-scoped → **never a cross-shard ANN search** |
| 7 | Read replicas safe here | Replica lag causes a cache **miss**, never a wrong answer |
| 8 | Outbox in control-plane + metering only; **not** on gateway hot path | Dropped telemetry is acceptable; +3 ms is not |
| 9 | Saga = **orchestration**, not choreography | A queryable state machine beats scattered event handlers |
| 10 | Hot path makes **zero** synchronous inter-service calls | Key/quota changes arrive via Kafka → local Redis |
| 11 | `MockLlmProvider` is the default; Ollama off by default | No GPU → 8–15 tok/s would distort every benchmark |
| 12 | ingress-nginx uses **least-conn**, not round-robin | Request durations span 2 ms → 8 s |
| 13 | Scale proven by **USL fit** at 1–6 pods, not a headline RPS number | 3 containers share 4 cores; "15,000 RPS" is not measurable locally |

### Deviations from the source blueprint (each needs an ADR)
1. Embedding source was unspecified → in-process.
2. Kafka drawn inside the request path → moved beside it.
3. PgBouncer in front of R2DBC → removed.
4. "3 × 5,000 = 15,000 RPS" → replaced with per-node curve + explicit USL extrapolation.
5. Leaky bucket → token bucket.

---

## 5. Task status

### ✅ P0 — Build scaffold (DONE, verified)
- [x] 9-module Maven reactor; `./mvnw validate` → BUILD SUCCESS (verified at 6 modules;
      **re-verify at 9** — 3 were added afterwards)
- [x] Maven wrapper bootstraps from nothing
- [x] Enforcer: Java ≥21, Maven ≥3.9
- [x] JaCoCo aggregate (80% line / 70% branch) in `coverage-report` module
- [x] PITest 70% mutation threshold, profile `-Pmutation`
- [x] SpotBugs (Max/Medium) + PMD (curated reactive ruleset) + Spotless, profile `-Pquality`
- [x] OWASP Dependency-Check, profile `-Psecurity`
- [x] SonarQube properties (local CE; CI overrides to SonarCloud)
- [x] `.gitignore`, `README.md`, `config/{pmd,spotbugs,owasp}`

### ✅ P1 — Design documents (DONE)
- [x] `docs/architecture/HLD.md` - problem, C4 L1 + L2, request sequence, multi-tenancy,
      ER model, scaling strategy, resilience, failure-mode table, deployment, tech
      choices, quality status, **and an explicit known-gaps section**
- [x] `docs/architecture/LLD.md` - threading model, C4 L3, per-module detail with code
      excerpts copied from the repo, schema table, build/quality config, container notes
- [x] `docs/architecture/scaling-model.md` - USL with the $N_{max}$ derivation, the
      measurement protocol, the billion-user arithmetic, unit economics
      (~$192M/yr at a 70% hit rate), and a 6-entry what-breaks-first ordering.
      **Every figure labelled (M) measured or (C) calculated**; there is no measured
      throughput number anywhere because P12 has not run
- [x] `docs/adr/` - **13 ADRs** plus an index, one per decision in section 4, each with
      Context / Decision / Consequences / Alternatives rejected. The 5 blueprint
      deviations map onto ADRs 0002, 0003, 0004, 0005, 0013
- [x] `docs/api/openapi.yaml` - **valid OpenAPI 3.1**, all 9 error statuses and 11 error
      codes, SSE documented, examples for tier1/tier2/upstream hits
- [x] `docs/README.md` - index with a reading order by question
- [x] `scripts/check-openapi.py` - validates the spec AND asserts it matches
      `GatewayError`; wired into the CI `infrastructure` job
- [x] 6 Mermaid diagrams (C4 L1/L2/L3, sequence, ER, deployment) - render natively on
      GitHub, no build step
- [x] README status line and roadmap corrected - it still claimed only P0 was done
- [x] All internal doc links verified to resolve (0 broken)

### ✅ P2 — Gateway core (DONE, running and verified end to end)
- [x] `common`: TenantId, ModelId, CacheKey, PromptNormalizer, CacheTier, SamplingParams
- [x] `common`: sealed `GatewayError` taxonomy + `GatewayException` (no stack-trace cost)
- [x] `common`: `LlmProvider` SPI, CompletionRequest/Response, TokenUsage
- [x] `common`: MurmurHash3 + immutable `ConsistentHashRing` (vnodes, replicas, rebalance)
- [x] `gateway`: functional router + handler, OpenAI-compatible wire format
- [x] `gateway`: `ApiKeyAuthFilter` (SHA-256 keys, constant-time compare, fail-closed)
- [x] `gateway`: `GatewayErrorWebExceptionHandler` (generic 500 for unknown exceptions)
- [x] `gateway`: `MockLlmProvider` + `ProviderRegistry` with ordered fallback chain
- [x] `gateway`: `CompletionPipeline` with bounded admission control
- [x] **102 unit tests passing**
- [ ] ArchUnit rules (no blocking calls, layering) — still to do

### ✅ P3 — Tier 1 + rate limiting (DONE, verified against real Redis)
- [x] `Tier1Cache`: reactive Redis exact-hash, never a hard dependency
- [x] `token_bucket.lua`: atomic, Redis server clock, documented
- [x] `TokenBucketRateLimiter` with fail-open/fail-closed policy
- [ ] Per-tenant budget counters with async flush to Postgres — still to do
- [ ] Testcontainers integration tests — still to do (verified manually so far)

### ✅ P4 — Tier 2 semantic cache (DONE, verified against real pgvector + real model)
- [x] `embedding`: `Embedding` value type (L2 invariant, cosine, pgvector interchange)
- [x] `embedding`: `EmbeddingModel` SPI
- [x] `embedding`: `OnnxEmbeddingModel` - DJL + ONNX Runtime, predictor pool, startup probe
- [x] `embedding`: `HashingEmbeddingModel` - deterministic lexical fallback for CI
- [x] `embedding`: `EmbeddingService` - bounded scheduler, never on the event loop
- [x] `scripts/fetch-model.sh` - fetches all-MiniLM-L6-v2 (87 MB, not in git)
- [x] Flyway `V1__semantic_cache.sql` - hash-partitioned by tenant, HNSW index
- [x] `Tier2SemanticCache` - ANN search, threshold, shadow mode, Tier-1 promotion
- [x] Wired into `CompletionPipeline` with the truncation guard
- [x] **38 embedding tests**; 140 total across common + embedding
- [ ] Testcontainers integration test for Tier 2 - still to do (verified manually)
- [ ] TTL eviction sweep - moves to the indexer service in P6

### ✅ P5 — Resilience and real providers (DONE, verified live)
- [x] `OpenAiCompatibleProvider` - one adapter for Groq/OpenRouter/Together/llama.cpp
- [x] `OllamaProvider` - implemented, off by default (CPU-only would distort benchmarks)
- [x] `MockLlmProvider` parameterised, plus a second instance for fallback demos
- [x] `ResilientLlmProvider` decorator: Retry(CircuitBreaker(TimeLimiter(Bulkhead)))
- [x] `ResilienceConfig` - breaker ignores 4xx so one bad client cannot trip it for all
- [x] Resilience4j metrics bound to Micrometer (not automatic with manual registries)
- [x] `ArchitectureRulesTest` - 9 rules, incl. no-block, no-sleep, injected Clock, layering

### ✅ P6 — Async services (DONE)
- [x] `GatewayEvents` - versioned event contracts shared by producer and consumers
- [x] `kafka-support` module - KafkaFactory, DeadLetterPublisher, EventConsumerRunner
- [x] Partition-ordered consumption (groupBy partition + concatMap within)
- [x] `metering` service - usage ledger, hourly rollup, idempotent on redelivery
- [x] `audit` service - append-only log, range-partitioned by time
- [x] `indexer` service - vector writes + batched TTL eviction
- [x] `GatewayEventPublisher` - fire-and-forget, documented as deliberately NOT outboxed
- [x] `control-plane` service - was the last empty module
- [x] `V1__control_plane.sql` - tenants, api_keys, saga_instances, control_outbox
- [x] `OutboxRelay` - FOR UPDATE SKIP LOCKED poller, multi-replica safe, no leader election
- [x] `SagaOrchestrator` + `SagaStep` + `SagaContext` - orchestrated, compensates backwards
- [x] Admin security: HTTP Basic on a separate port, distinct from tenant API keys
- [x] All 10 modules build and pass; **no empty modules remain**

### ✅ P8 — Kubernetes (DONE, chart + cluster verified offline)
- [x] Helm umbrella chart - 10 templates, one `range` over `.Values.services` rather
      than five near-identical copies that drift
- [x] `values-{dev,scale,full,obs}.yaml` - all four `helm lint` clean and render
- [x] `deploy/k3d/cluster.yaml` - 1 server + 1 agent, traefik and servicelb disabled,
      host 8080 -> nodePort 30080
- [x] `scripts/cluster-up.sh` - preflight (tools, daemon, **RAM**), k3d, MetalLB with a
      pool derived from the live Docker subnet, ingress-nginx with pinned nodePorts
- [x] `scripts/cluster-down.sh` - stop by default, `DELETE=1` to remove
- [x] `deploy/k8s/prometheus-adapter/values.yaml` - exposes
      `gateway_requests_per_second` from `http_server_requests_seconds_count`,
      actuator probe traffic excluded
- [x] HPA v2 on RPS with a CPU fallback; verified rendered: metric name matches the
      adapter rule exactly, min/max 2/6, scaleUp 30 s / scaleDown 300 s
- [x] Verified rendered: no CPU limit, memory request == limit, `replicas` omitted when
      the HPA owns it, `maxUnavailable: 0`, startup probe gates liveness,
      liveness != readiness, ConfigMap checksum annotation, SA token not mounted
- [x] ServiceMonitor gated on the CRD existing (renders with
      `--api-versions monitoring.coreos.com/v1`), PDB gated on replicas > 1

**NOT yet verified against a running cluster.** k3d is not installed and this machine has
~460 MB available against the ~2000 MB the cluster needs. The scripts fail loudly rather
than half-starting (gotcha 35/36 are encoded in them), but nothing here has been
exercised end to end. Do that before P12: `./scripts/cluster-up.sh`.

### ✅ P10 — Observability (DONE as code; **not run against a cluster**)
- [x] `deploy/k8s/observability/kube-prometheus-stack-values.yaml` - 15 s scrape,
      6 h retention, Alertmanager off, `*SelectorNilUsesHelmValues: false` (gotcha 44),
      annotation-based fallback scrape job carrying the namespace/pod relabelling the
      adapter requires, k3s control-plane jobs disabled so Targets is not permanently red
- [x] `deploy/k8s/observability/loki-values.yaml` - SingleBinary, filesystem, every
      cache and canary off (gotcha 45)
- [x] `deploy/k8s/observability/tempo-values.yaml` - OTLP on 4317 + 4318, local storage,
      span-derived metrics deliberately OFF (they would describe 10% of traffic while
      looking authoritative beside Micrometer counters that see all of it)
- [x] `deploy/k8s/observability/alloy-values.yaml` - DaemonSet, filtered to
      `part-of: ai-gateway`, promotes namespace/pod/component to match the Prometheus
      series, `trace_id` to structured metadata rather than a label (gotcha 46).
      **The log regex was verified against real log lines through both the YAML and the
      Alloy escaping layers**, including the empty-trace-id case
- [x] `deploy/k8s/prometheus-adapter/values.yaml` **rewritten** - derives
      `gateway_requests_per_second` from the gateway's own timer rather than
      `http_server_requests` (gotcha 49), plus `gateway_request_p99_seconds` and
      `gateway_cache_hit_ratio`
- [x] `deploy/grafana/dashboards/gateway-red.json` - 17 panels: Rate / Errors /
      Duration / Scaling
- [x] `deploy/grafana/dashboards/cache-economics.json` - 12 panels, including the
      shadow-mode false-hit measurement and a projected-saving stat
- [x] `scripts/build-dashboards.py` - the dashboards are GENERATED; verified
      deterministic, and CI fails if the committed JSON drifts from the generator
- [x] `scripts/check-dashboards.py` - every metric verified to exist (gotcha 43); in CI
- [x] `scripts/observability-up.sh` - 4 profiles (metrics/logs/traces/full) with a RAM
      preflight, dashboards loaded as labelled ConfigMaps, and Grafana datasources with
      **Tempo<->Loki correlation** so the trace id in every log line is actually used
- [x] Chart: `monitoring.tracing.*` wired to `MANAGEMENT_OTLP_TRACING_ENDPOINT`, off by
      default (gotcha 47). Verified absent by default and present when enabled
- [x] Colours from a **validated** categorical palette - the 4-slot dark subset passes
      all six checks against the dark surface; colour is bound per entity (tier1 always
      blue, tier2 always orange) rather than by query order, and no panel has two y-axes

**Not verified against a running cluster**, same reason as P8: k3d is not installed and
this machine has ~460 MB available. Everything checkable without a cluster has been
checked - YAML parsing, dashboard structure, metric existence, the Alloy regex, Helm
rendering, and the script argument handling.

### 🟡 P11 — Testing and quality (gateway gate CLEARED; Tier 2 + PITest remain)
- [x] **`gateway`: 32.7% -> 80.7% line, 35.8% -> 81.7% branch** - clears the 80/70 gate
      for the first time. 9 new test classes, 166 new tests, 252 passing in the module
- [x] `OpenAiCompatibleProviderTest` (28) and `OllamaProviderTest` (21) against
      **WireMock**, not a mocked WebClient - SSE framing, the `[DONE]` sentinel, a
      malformed chunk mid-stream, and every row of the status-to-taxonomy mapping
- [x] `ApiKeyAuthFilterTest` (25) + `RedisApiKeyRegistryTest` (16) - written around what
      would be a VULNERABILITY: the rejection message never separates "unknown" from
      "revoked"; the presented key is never echoed; the exempt-path check is a prefix
      match not a contains match; `tenantOf` throws rather than inventing an anonymous
      tenant; lookup is by SHA-256 only; auth fails **closed** on a Redis failure
- [x] `GatewayErrorWebExceptionHandlerTest` (26) - a parameterised sweep asserting all
      11 errors against the status, code, type and `retryable` flag in
      `docs/api/openapi.yaml`, plus that no internal detail escapes in a 500
- [x] `Tier1CacheTest` (18), `TokenBucketRateLimiterTest` (12), `TokenBucketGateTest` (6),
      `GatewayEventPublisherTest` (12)
- [x] SpotBugs, PMD, CPD and Spotless clean after the changes
- [x] JaCoCo gate made real (gotcha 22) and enforced per module in `-Pquality`
- [x] Failsafe bound to the lifecycle - `*IT` tests were compiled and never run before
- [x] Tests run in UTC, matching the services
- [x] SonarCloud green in CI; SonarQube still blocked locally (needs ~2.5 GB)
- [ ] **`Tier2SemanticCache` - 100 missed lines.** Deliberately left: better served by a
      Testcontainers IT against real pgvector than by mocking `DatabaseClient`
- [ ] **PITest mutation run** - configured, still never executed
- [ ] Verify-phase aggregate - Docker Desktop was not running, so it could not be
      reproduced locally. CI's `coverage-gate` job computes it. Unit-only aggregate is
      **62.5% line / 68.1% branch**; see gotcha 52 before comparing that to the 51.7%
      recorded earlier, which was a verify-phase figure

**Three production bugs found by writing these tests**, each now pinned:

1. **`Tier1Cache` double-counted misses.** The counting `doOnSuccess` sat after the
   `flatMap`, so it observed the deserialised outcome rather than the Redis result and
   re-counted what `deserialize()` had already counted. See gotcha 53 - this deflated
   the hit ratio, worst during a rolling deploy.
2. **`Tier2SemanticCache` had the same bug plus two silent paths.** A below-threshold
   candidate scored twice, and a candidate suppressed by the non-semantic fallback model
   or withheld by shadow mode was recorded as a plain miss - indistinguishable from
   "nothing similar exists", which is the distinction that matters most during a Tier-2
   rollout. Both now have their own outcome, and the two tiers now count identically.
3. **`GatewayErrorWebExceptionHandler` threw an NPE from inside itself** on an
   `IllegalArgumentException` with no message (gotcha 56).

**Two cases where the test was wrong and the code was right** - corrected the test:
`TokenUsage.cacheHit()` zeroes completion tokens deliberately, and the event assertions
had to move to the wire field names with a Boot-configured mapper (gotchas 54, 55).

### ✅ P13 — CI/CD (DONE)
- [x] `deploy/docker/Dockerfile` - multi-stage, non-root (uid 10001), JRE-only runtime
- [x] `deploy/docker/strip-native-libs.sh` - **ONNX 130 MB to 7 MB**, verified
- [x] `.github/workflows/ci.yml` - 8 parallel jobs: build, integration, static analysis,
      coverage gate, security (gitleaks + OWASP), SonarCloud, images (+Trivy), infra
- [x] `Jenkinsfile` - same stages declaratively, for the self-hosted path
- [x] `.github/dependabot.yml` - grouped weekly updates, springdoc/DJL majors pinned
- [x] Images push to **GHCR (primary)** and **Docker Hub (secondary, opt-in)**
- [x] Trivy scans the image **before** it is pushed, not after
- [x] `deploy/docker/README.md` - how to enable the Docker Hub push, no secrets in repo

### ✅ P7 — Sharding & replication (DONE; whale sub-sharding still designed only)
- [x] **`ShardRouter`** in `common/shard` - owns membership while requests are in
      flight. The ring stays immutable; the router swaps it wholesale in an
      `AtomicReference`, so a reader sees the ring before or after a change, never a
      half-updated one, and never blocks. Add/remove use compare-and-set loops
- [x] **Refuses to remove the last shard** - an empty ring cannot route, so a
      decommission would otherwise be a total Tier-2 outage presenting as an exception
      on every request
- [x] **`ShardedDatabase`** - `forRead(tenantId)` / `forWrite(tenantId)`. The shard key
      is applied in exactly one place, and every Tier-2 query already carries
      `tenant_id`, so a request touches ONE shard and there is never a cross-shard ANN
      search (ADR-0006)
- [x] **Read replicas implemented** (ADR-0007) - reads prefer a replica, writes always
      the primary. Round-robin per shard, `Math.floorMod` (gotcha 59)
- [x] **`ShardProperties`** - `gateway.sharding.*`. Credentials are NOT per shard; they
      come from `spring.r2dbc` and are applied LAST when building connection options, so
      a shard URL cannot smuggle in a different user
- [x] **The unsharded path is unchanged and still the default** - no config means one
      shard called `default` on the auto-configured datasource. All four ITs and the
      whole local setup were untouched
- [x] Per-shard pools are 8, replicas 4 (vs 16 unsharded): total connections are
      pods x shards x pool size, and 20 pods x 4 shards x 16 would be 1,280 - well past
      a default `max_connections` of 100
- [x] Three configuration mistakes now refuse to boot, each tested: duplicate shard ids
      (the ring takes a Set, so they would collapse silently and half the capacity would
      never be used), a shard on the ring with no client (otherwise ONE tenant fails
      every request while all others are fine), and an unparseable URL
- [x] **42 new tests.** `ShardRouterTest` routes 80,000 times across four threads while
      a fifth churns the topology, and drives 32 concurrent additions to prove the CAS
      loop loses none. `ShardedDatabaseTest` asserts a tenant's read and write land on
      the SAME shard - if they diverged, writes would go to one database and lookups
      search another, giving a cache that never hits with no error anywhere.
      `ShardConfigTest` uses `ApplicationContextRunner` because the gateway module has
      no context test at all, so a bean-wiring mistake would surface only at startup
- [x] The rebalance test the plan listed as outstanding **already existed** in
      `ConsistentHashRingTest` and is thorough: ~K/N keys move, keys only ever move TO
      the new shard, and modulus sharding would move far more

**Remaining, and now the honest P7 gap:**
- [ ] **Whale-tenant sub-sharding.** `ShardRouter.route(String)` accepts a
      `tenant#bucket` key, but nothing computes those buckets, so one enormous tenant
      still saturates one shard
- [ ] **Nothing alarms on replica lag.** ADR-0007 says monitoring is required, because
      unbounded lag silently degrades the hit rate - a cost regression with no error
      anywhere. The cache dashboard would show a falling hit ratio, which is the symptom
      rather than the cause
- [ ] Helm does not express a topology. Deliberate: a list of shards is awkward as
      environment variables, and there is no multi-database deployment to point it at on
      this hardware

### ⬜ P9 — Terraform  ← **CURRENT** (validate-only; needs no cluster and no AWS spend)
- [ ] `terraform/local/` - k3d + helm providers, **applied**
- [ ] `terraform/aws/` - EKS/ElastiCache/Aurora/MSK, **validate + tflint + checkov +
      Infracost only** (never applied - no AWS spend)

### ⬜ P12 — Load testing (blocked on P8 cluster + P10 Prometheus)
- [ ] k6 scripts, cpuset pinning (k6 -> cores 6-7, SUT -> 0-5)
- [ ] 1-6 pod sweep, USL fit, performance report

### ⬜ P14 — Dashboard
- [ ] React + TypeScript + Vite tenant dashboard

---

## 5b. Verified working (session 1, live run)

Run: `docker run -d --name aigw-redis -p 6379:6379 redis:7-alpine`
then `SERVER_PORT=8099 java -Xmx256m -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar`

| Behaviour | Result |
|---|---|
| Startup | 8.9 s, 256 MB heap |
| `/actuator/health` | `{"status":"UP","groups":["liveness","readiness"]}` |
| Cache miss → hit | 62.9 ms → 12.5 ms end-to-end (**~5x**; internal lookup is ~1 ms) |
| Tenant isolation | `globex` sending `acme`'s exact prompt got `upstream`, NOT acme's entry |
| Redis key schema | `{tenant:acme}:c1:<sha256>` and `{tenant:acme}:rl` — hash tags correct |
| Cache TTL | 3540 s remaining of configured 1 h |
| Missing API key | 401 `unauthenticated` |
| Invalid API key | 401, message does not reveal whether the key ever existed |
| Invalid body | 400 with sorted, deterministic violation list |
| Rate limit | capacity 5 / refill 1 per s → 6 OK then 429 with `Retry-After: 1` |
| Token bucket refill | fractional refill confirmed correct (100 → 61.44 under load) |

### P4 verified live (2026-11-02)

Stack: `aigw-redis` (6379), `aigw-pg` pgvector 0.8.7/pg17 (**host port 55432**),
gateway on 8099 with `EMBEDDING_MODEL_PATH` set and `-Duser.timezone=UTC`.

**Real similarity scores from all-MiniLM-L6-v2:**

| Pair | Cosine | At threshold 0.95 |
|---|---|---|
| "How do I reset my password?" / "What is the way to reset my password?" | 0.9789 | served |
| ... / "I forgot my password, how can I change it?" | 0.8994 | refused |
| ... / "What is the capital of France?" | 0.1422 | refused |

That middle row is the case for shadow mode: arguably the same question, correctly
declined by a conservative threshold.

**Serving behaviour with shadow mode off:**

```
"How do I reset my password?"                 -> upstream
"What is the way to reset my password?"       -> tier2_semantic   <- paraphrase hit
"What is the way to reset my password?"       -> tier1_exact      <- promoted to Tier 1
"I forgot my password, how can I change it?"  -> upstream         <- 0.90 < 0.95
"What is the capital of France?"              -> upstream
```

**Measured latency** (Prometheus, after warm-up, n=31-34):

| Tier | Target | Measured mean | Note |
|---|---|---|---|
| tier1_exact | < 2 ms | 3.94 ms | over target: Redis is in Docker/WSL2, so every lookup crosses a VM boundary; includes JSON decode |
| tier2_semantic | < 15 ms | 15.38 ms | on target, including ~6 ms ONNX inference + HNSW search |

Counters after the run: tier1 31 hits / 35 misses, tier2 30 hits / 4 below-threshold /
5 misses. Partition pruning confirmed: all of tenant `acme`'s rows land in
`prompt_cache_p3` alone.

### P5/P6 verified live (2026-11-02)

**Circuit breaker + fallback** (primary mock at `failure-rate=1.0`, fallback `mock-secondary`):

| | state | failed | successful | rejected without attempting |
|---|---|---|---|---|
| `mock` | **open** | 10 | 0 | 18 |
| `mock-secondary` | closed | 0 | 14 | 0 |

Breaker opened after exactly 10 calls (`minimumNumberOfCalls`), then shed without touching
the failing provider. All 14 user requests still succeeded.

**End-to-end event flow** (gateway → Kafka → metering → Postgres): 30 events published,
**0 dropped**; tenant-hash partitioning put `acme` on `usage_ledger_p0` and `globex` on
`p3`; consumer lag 0.

**Idempotency under full redelivery** - the property billing depends on:

| | Before | After forced replay of all 30 events |
|---|---|---|
| Ledger rows | 18 | 30 |
| Rollup requests | 18 | 30 |
| Duplicates detected | - | **18** |

18 redelivered events were recognised and skipped - no double-billing. The 12 new rows
were the original batch lost to the Flyway bug above, correctly recovered by the replay.
Critically **rollup == ledger rows** throughout: the aggregate did not drift, because it
is only incremented when the ledger insert actually inserted.

**ArchUnit caught a real bug within minutes of being written:** `CompletionPipeline`
called `Instant.now()` directly instead of the injected `Clock`, which would have made
Tier-2 TTL untestable without sleeping. It had already caught the same thing in
`Tier2SemanticCache`.

### HONEST COVERAGE POSITION (measured 2026-11-02)

The gate is now real and **currently fails**, which is correct - it reflects reality.

| Module | Line coverage | Against 80% gate |
|---|---|---|
| common | 63% | fails |
| embedding | 61% | fails |
| kafka-support | 0% | fails |
| gateway | 0% | fails |
| control-plane | 0% | fails |
| metering | 0% | fails |
| indexer | 0% | fails |
| audit | 0% | fails |
| **aggregate** | **17.8% line, 20.4% branch** | |

211 tests exist and all pass, but they cover `common` and `embedding` almost exclusively.
The gateway has one test file (ArchUnit, 9 rules) and the four newer services have none.
`./mvnw verify` passes; `./mvnw verify -Pquality` fails on coverage, by design.

**Closing this gap is the single largest remaining piece of work** and should come before
any further feature work.

### SonarQube: blocked locally, belongs in CI

SonarQube Community embeds Elasticsearch and needs ~2.5 GB. Measured free RAM on this
machine with Docker Desktop, Chrome and VS Code running: **0.4-0.6 GB**. It cannot run
here alongside anything else.

The analysis value was obtained anyway, server-free, via SpotBugs + PMD + CPD, which is
what the `-Pquality` profile runs. **SonarCloud** (free for public repositories) is the
right home for the dashboard: CI runners have the RAM, and the token lives in GitHub
Actions secrets rather than on this laptop. Wire it in P13.

### P11 static analysis results (2026-11-06)

Started at 106 PMD + 14 SpotBugs findings. **Nine were real and were fixed:**

| Finding | Module | Fix |
|---|---|---|
| 8x `UnnecessaryCast` | common | Removed redundant `(long)` casts in MurmurHash3; the `& 0xffL` mask already widens |
| 4x `NP_NULL_ON_SOME_PATH` | gateway | `Row.get(...,Long.class)` unboxed into a `long` record component - NPE on a NULL column, and the caller's `onErrorResume` would have swallowed it as a cache miss |
| 1x `NP_NULL_ON_SOME_PATH` | control-plane | Same pattern in `OutboxRelay` |
| `UnusedFormalParameter` | embedding | `probeDimension(pool, model)` never used `model` |
| `CognitiveComplexity` 16 | gateway | **Hand-rolled JSON unescaping in `extractDelta` replaced with Jackson.** The original matched any `"content"` substring and handled only the escapes I thought of |
| `AvoidCatchingGenericException` | gateway | Narrowed the Ollama and OpenAI chunk parsers to `JsonProcessingException` |
| `UseObjectForClearerAPI` | gateway | `publishAudit` took 6 args, 4 of them Strings - now takes an `AuditContext` record |
| `CT_CONSTRUCTOR_THROW` | gateway | `ProviderRegistry` made `final`, removing the finalizer-attack surface rather than suppressing |
| `UseUnderscoresInNumericLiterals` | gateway | `131072` -> `131_072` |

The other ~110 were false positives for this codebase - rules predating records
(`OverrideBothEqualsAndHashCodeOnComparable` on record value types), reactive idioms
(`isHealthy()` returning `Mono<Boolean>`), DI (`EI_EXPOSE_REP2` on every injected
singleton), a pooled resource PMD wanted closed (closing it would drain the pool), and 67
`CouldBeFinal` style preferences. Every exclusion names why it does not apply here.

### P13 verified live (2026-11-06)

**Image build and run:**

| | |
|---|---|
| ONNX dependency | 130 MB -> **7 MB** (kept linux-x64 only) |
| Final image | 309 MB |
| Memory in a 512Mi limit | **263 MB (51%)** - MaxRAMPercentage=70 working |
| User | uid 10001, no shell, no home |
| Startup | 15.8 s |
| Health + real request | both served from the container |

Platform sizes confirmed inside the jar: win-x64 352 MB, osx-x64 104 MB,
osx-aarch64 99 MB, linux-x64 20 MB, linux-aarch64 17 MB.

### Docker Hub

Namespace is **not** hardcoded. To enable the push, set in GitHub
Settings -> Secrets and variables -> Actions:

| Kind | Name |
|---|---|
| Variable | `DOCKERHUB_NAMESPACE` |
| Secret | `DOCKERHUB_USERNAME` |
| Secret | `DOCKERHUB_TOKEN` (an access token, not the password) |

Both push steps are guarded on `DOCKERHUB_NAMESPACE`, so forks and clones are never
broken by a credential they cannot have. GHCR remains primary - it authenticates with
the built-in `GITHUB_TOKEN`, so there is nothing to manage.

### RESOLVED: container image size

The `embedding` module pulls `onnxruntime`, whose jar bundles natives for 5 platforms:

| Platform | Size |
|---|---|
| win-x64 (incl. CUDA/TensorRT) | 351 MB |
| osx-x64 | 103 MB |
| osx-aarch64 | 99 MB |
| **linux-x64 (the only one a container needs)** | **19.7 MB** |
| linux-aarch64 | 16.8 MB |

Fat jar is 233 MB as a result. **Fix planned for P8:** a hand-written Dockerfile that
strips non-target natives from `BOOT-INF/lib/onnxruntime-*.jar` instead of using
buildpacks. Expected image reduction ~130 MB per service. Runtime RAM is unaffected —
only the current platform's library is mapped.

## 6. Open items needing the user

- [ ] `gh auth login` and `docker login` — then pushing becomes possible
- [ ] Permission for `docker system prune -a` (~11 GB) before pulling heavy images
- [ ] Permission for the first git commit
- [ ] Optional: a free-tier LLM API key (Groq / OpenRouter) if a real provider demo is wanted

---

## 7. Conventions

- Java package root: `io.vasanth.gateway`
- Module = service, except `common` and `embedding` which are libraries
- Tests: `*Test.java` = unit (surefire), `*IT.java` = integration (failsafe, Testcontainers)
- Every architectural decision gets an ADR in `docs/adr/NNNN-title.md`
- No Lombok — Java 21 records and sealed interfaces instead
- **Update this file at the end of every work session.**
