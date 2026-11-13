# PROGRESS — session handover file

**Purpose:** if the session is lost, read this file top to bottom and you have everything
needed to continue without re-deriving decisions.

**Last updated:** 2026-11-11 (session 3 — P8 and P10 verified live, P12 blocked)
**Repo:** `C:\Users\vasan\OneDrive\Desktop\claudeProjects\ai-gateway`
**Git:** branch `main`, 33 commits, **pushed to
`github.com/VasanthvasanthM/multi-tenant-ai-gateway`** (HEAD `835736e`)

---

## 0. PENDING WORK — start here

Everything below is current as of 2026-11-11. Phases P0–P11, P13 and P14 are
done; P8 and P10 are verified against a live cluster. Two things are open.

### 0.1 RESOLVED — `Tier2SemanticCacheIT` (was 7 of 18 failing in CI)

**Fixed. CI is green on `main` as of run on `1ee7405`:** Integration test,
SonarCloud, Build and unit test, Static analysis, Security scan, Dashboard,
Validate infrastructure and all five image builds pass.

**Root cause: a fixed-clock time bomb in the test.** `NOW` was a hardcoded
`Instant` literal. `store()` writes `expires_at` from that clock while the
lookup filters on `expires_at > now()`, which is POSTGRES' wall clock - two
different clocks, which agree in production only because it injects
`Clock.systemUTC()`. Once real time passed the literal + `tier2Ttl` (24 h),
every row the test stored was already expired from the database's point of
view, so every lookup returned nothing. That failed exactly the seven tests
expecting a served hit and passed the eleven expecting a refusal, on an
untouched tree, a day after the suite was green.

The fix is `NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS)`, with the
reasoning written into the file above the constant. It is still `Clock.fixed`
rather than `systemUTC` because `expiresAtComesFromTheClock` asserts an exact
instant, and truncated to seconds because Postgres stores microseconds while
`Instant.now()` carries nanoseconds.

**Two process failures worth remembering, both mine:**

1. **I ruled this hypothesis out by reading the already-fixed file.** Session 3
   listed "fixed-clock expiry time-bomb" as ruled out, with the evidence
   "`NOW = Instant.now()`, not a hardcoded date". That was read off the WORKING
   TREE after the fix had landed on disk, so it proved nothing about the commit
   CI was failing on. When a test fails in CI and passes locally, diff the
   failing ref against the working tree first - `git show <ref>:<path>` - before
   reasoning about either.
2. **The fix was committed under an unrelated message by `git add -A`.** Commit
   `54dfc18` says "Clear the Tier-2 IT of being a code bug" and describes a
   PROGRESS.md edit; it also carries 21 lines of `Tier2SemanticCacheIT.java`
   that were sitting modified on disk and were not written in that session. The
   real fix is therefore buried in a commit whose message denies there was a bug
   - `git log` on this file is misleading as a result. Stage deliberately and
   read the diff before committing.

**Still red, both pre-existing and both `continue-on-error: true`** (which is
why a run can report *success* while they fail - read the JOBS, not the run
conclusion):

### Coverage gate — two of three modules fixed, `kafka-support` remains

`jacoco:check` has an 0.80 line / 0.70 branch rule at BUNDLE level and stops at
the first violating module, so they surface one at a time:

| Module | Was | Now | How |
|---|---|---|---|
| `common` | 0.690 | **0.885** | Real tests for the value types that had ZERO coverage - `CompletionResponse`, `CacheTier`, `StreamUsage`, 12 `GatewayError` variants |
| `embedding` | 0.607 | **passes** | `OnnxEmbeddingModel` excluded from the rule; every path needs the 90 MB MiniLM artifact that is in neither the repo nor CI, so covering all other lines still lands at 0.66 |
| `kafka-support` | 0.253 | **0.980** lines / 0.875 br | Real tests for `KafkaFactory` + `EventConsumerRunner`, no exclusion, no broker |
| `gateway` | — | **passes** | Was already fine; only reached once the three above stopped failing first |
| `control-plane` | **0.70 lines / 0.50 br** | same | **NOT DONE — this is the current blocker** |
| `metering`, `indexer`, `audit` | unknown | unknown | Never reached; the gate stops at the first violation |

**Read this before estimating the remaining work.** `jacoco:check` halts the
reactor at the FIRST violating module, so fixing one only reveals the next.
Four have been cleared and at least one remains; `metering`, `indexer` and
`audit` have still never been evaluated, because no run has got past
`control-plane`. Any statement that the gate is "nearly green" is a guess until
a run reaches `coverage-report`.

The cheap way to see the whole picture at once, instead of one module per
10-minute run: temporarily set `-Djacoco.haltOnFailure=false` (or
`-Dmaven.test.failure.ignore=true` alongside it) so every module reports its
own ratio in a single pass, then plan from the full list.

`control-plane` is at 0.70 lines against 0.80 and **0.50 branches against
0.70** - the branch gap is the larger one and usually means untested
conditionals rather than untouched classes, so start from its jacoco.csv
BRANCH_MISSED column rather than LINE_MISSED.

`kafka-support` is DONE - see the table above. What follows was its analysis and
is kept only because the same reasoning applies to `control-plane` next: look
for classes the tests never construct, check whether they genuinely need
infrastructure before excluding anything, and assert the invariant the code's
own comments promise rather than touching lines to move a number.

### Mutation testing — the only red job left

Threshold is `mutation.threshold` = **70**. The job runs
`-pl common,embedding,kafka-support,gateway --fail-at-end`.

| Module | Score | |
|---|---|---|
| `common` | 83% | passes |
| `kafka-support` | 11% → **74%** | passes - the coverage tests did it |
| `embedding` | 49% → **60%** | **FAILS** |
| `gateway` | — | **never produces a score; see below** |

**`embedding`: 45 SURVIVED + 4 NO_COVERAGE of 124 mutations. Needs ~12 more
kills.** Survivors by class:

| Class | Survivors | Dominant mutator |
|---|---|---|
| `HashingEmbeddingModel` | 22 | `MathMutator` |
| `Embedding` | 14 | `ConditionalsBoundary`, `RemoveConditional` |
| `EmbeddingService` | 13 | `RemoveConditional`, `VoidMethodCall`, `NullReturnVals` |

**Two wrong predictions made here, recorded so they are not repeated:**

1. Excluding `OnnxEmbeddingModel` from PITest (it was excluded from JaCoCo but
   not from PITest - the two gates disagreed about the same class) was predicted
   to be "most of the distance" to 70%. It removed 27 mutations and moved the
   score 49% → 60%. Worth doing, roughly half of what was claimed.
2. `EmbeddingArithmeticTest` - 16 tests pinning exact cosine values and both
   sides of each guard - was predicted to kill ~11. It killed **one**.

Why (2) failed is the useful part. `ConditionalsBoundary` flips `>` to `>=`, so
it changes behaviour at EXACTLY the boundary and nowhere else. The test passes
1.00005 (inside the 1e-4 tolerance) and 1.01 (outside), which both sides of the
mutant agree on. Killing it needs `|magnitude - 1.0|` to be exactly
`NORM_TOLERANCE`, not merely near it - and the same applies to every other
boundary mutant in the module.

So the remaining work is not "more tests"; it is tests constructed AT the
specific edges, plus a known-answer test for `HashingEmbeddingModel` that pins
exact vector components. `MathMutator` survives anything short of that: swapping
an operator still produces a deterministic, unit-length, 384-dimension vector,
so determinism, norm and dimension assertions all pass against the mutant.
`common/src/test/.../MurmurHash3KnownAnswerTest.java` is the pattern to copy.

**`gateway` has still never been measured.** Only three scores appear in the
job output for four modules. Find out why before trusting the other three -
a module that silently produces no score is indistinguishable from one that
passes.

### 0.2 P12 — the load campaign has no valid USL fit

Harness is finished and self-validating. Four runs, four refusals from the
fitter. Full detail in `docs/architecture/scaling-model.md` §3b. Short version:
this laptop cannot host the measurement — both k3d "nodes" are containers on
one 3-core / 2.9 GB WSL VM, so Kubernetes believes it has 6 CPUs and 5.8 GiB,
and total throughput stayed within ~25% of 300 rps at every pod count, which is
a shared ceiling rather than per-pod scaling.

To finish it, on a host where 3+ pods each get a real core:

```bash
./scripts/cluster-up.sh
kubectl apply -f deploy/k8s/dev-infra.yaml
helm install aigw deploy/helm/ai-gateway -f deploy/helm/ai-gateway/values-dev.yaml \
  --set global.imageRegistry=ai-gateway-local --set global.imageTag=0.1.0 \
  --set services.gateway.resources.limits.cpu=400m
MAX_PODS=4 ./loadtest/scripts/capacity-sweep.sh
```

### 0.3 Smaller open items

- **3 Dependabot PRs failing**, one bumping Spring Boot 3.5.16 → 4.1.2. A major
  Spring Boot jump across 9 modules is a decision, not a fix — needs the user.
- A **dev-only Postgres password** (`aigw-dev-only`) is committed in
  `deploy/k8s/dev-infra.yaml`, clearly marked. Replace with a generated secret
  if that repo ever stops being a personal one.
- `docs/architecture/scaling-model.md` still carries `(C) calculated`
  throughput figures. Correct as labelled; they become `(M)` only when 0.2 is done.
- P10's observability stack was installed and verified, then the machine
  restarted. Re-run `SKIP_RAM_CHECK=1 ./scripts/observability-up.sh --profile metrics`
  to bring it back; it is idempotent.

### 0.4 Live-environment facts discovered the hard way

- Docker Desktop was **not running** on 2026-11-11 after a restart, so the k3d
  cluster and the monitoring namespace are gone. `./scripts/cluster-up.sh`
  rebuilds; it reuses an existing cluster if one is there.
- Host ports 8080/8443 collide with the user's Tomcat. Use
  `HTTP_PORT=18080 HTTPS_PORT=18443`.
- k6 lives at `/c/Users/vasan/AppData/Local/Programs/k6`; k3d at
  `/c/Users/vasan/AppData/Local/Microsoft/WinGet/Packages/k3d.k3d_*`. Neither is
  on PATH by default.
- `kubectl scale`/`kubectl patch` take server-side-apply ownership of fields the
  chart manages; `helm upgrade` then fails with a field-manager conflict and
  `--take-ownership` does NOT fix it (it only covers Helm's annotations). The
  release has to be reinstalled.
- `helm upgrade --reuse-values` reuses the PREVIOUS release's values, which win
  over the chart's, so a changed default in `values.yaml` never reaches an
  existing release — and the upgrade still reports `STATUS: deployed`.

---

## 1. How to resume in one minute

```bash
cd "C:/Users/vasan/OneDrive/Desktop/claudeProjects/ai-gateway"
./mvnw -B -ntp validate       # should be BUILD SUCCESS across 9 modules
cat PROGRESS.md               # this file
cat README.md                 # architecture + stack
ls docs/adr/                  # why each decision was made
```

Then read **section 0 (PENDING WORK)** - it is the current state. Section 5 is the
per-phase history.

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
61. **`spotbugs:check` on its own is NOT what CI runs, and it is less strict.** The
    static-analysis job runs
    `verify -Pquality -DskipITs -Dcoverage.line.minimum=0 -Dcoverage.branch.minimum=0`,
    which lowers the SpotBugs threshold to Medium and adds `spotless:check`. Running the
    goals individually passed locally while CI failed on
    `EI_EXPOSE_REP`. **Reproduce the job, not the goals** - the full command is in
    `.github/workflows/ci.yml` and takes about 3 minutes.
62. **A getter that returns a mutable collaborator is an API bug, not just a SpotBugs
    finding.** `ShardedDatabase.router()` handed out the live `ShardRouter`, so any
    caller could add or remove a shard through a getter on the database facade.
    Replaced with topology FACTS (`shardCount()`, `shards()`, `virtualNodes()`).
    Changing a topology should be a deliberate named operation, not a side effect of
    asking a question about it.
63. **`tflint --chdir=X` looks for `.tflint.hcl` inside X, not at the repository root.**
    So `tflint --chdir=terraform/aws` with the config at the root runs with NO ruleset
    loaded: the aws plugin is never installed, nothing is checked, and the step passes.
    A gate that always passes is worse than no gate (same class as gotcha 22). Always
    pass `--config` explicitly, and prove it worked with
    `tflint --config=... --version`, which lists the loaded rulesets.
64. **A Windows-generated `.terraform.lock.hcl` breaks a Linux runner.** The lock
    records per-platform hashes, so one generated locally contains only
    `windows_amd64` and CI fails with "does not have a package available for
    linux_amd64" - which reads as a broken provider. Generate with
    `terraform providers lock -platform=linux_amd64 -platform=windows_amd64`, and DO
    commit the result: without it every `init` re-resolves inside the `~>` constraint
    and a provider version can change between a local run and CI with nothing recording
    it.
65. **HCL does not allow a value on the line after `=`.** A long interpolated map key
    tempts you to wrap, and `"${...}:sub" =` followed by the value on the next line
    fails with "Expected the start of an expression". Use a `locals` entry to shorten
    the key instead.
66. **Writing a comment that describes the opposite of the code happened FOUR times in
    this phase.** A connection-options comment said "the URL wins" when base options
    were applied last and therefore won; two tflint rules were annotated "OFF" while
    set to `enabled = true`; a KMS policy comment promised a `kms:ViaService` condition
    that was not there; a flow-log comment said "30-day retention" after it became a
    variable at 90. Three were fixed by correcting the comment and one by adding the
    condition the comment promised. **Re-read every comment adjacent to a line being
    changed** - a wrong comment is worse than none, because it is trusted.
67. **`import.meta.env.VITE_*` is a COMPILE-TIME substitution, not a runtime lookup.**
    Vite replaces it with a literal when it transforms the module, so `vi.stubEnv`
    cannot change it and neither can moving the read inside a function. Every dashboard
    app test rendered the "no API key configured" panel until the value was supplied at
    BUILD time via `dashboard/.env.test` (committed, and un-ignored explicitly).
68. **A relative URL cannot construct a `Request` outside a browser.** `new
    Request('/v1/usage/summary')` throws `Failed to parse URL` under jsdom, because only
    a browser resolves a relative URL against a location. RTK Query builds a Request
    internally, so every integration test failed with a `TypeError` that looked like a
    mocking problem. Fixed properly rather than papered over: the base URL is now
    `window.location.origin`, which is equivalent in a browser and absolute in jsdom
    (which also needs `environmentOptions.jsdom.url`, since `about:blank` has origin
    "null").
69. **`fetchBaseQuery` captures the global `fetch` when the API is CREATED**, which is at
    module import - before any test can install a stub. Pass
    `fetchFn: (...args) => globalThis.fetch(...args)` so it resolves per call. Identical
    in a browser; the indirection exists only so it can be tested.
70. **RTK Query passes a `Request` OBJECT to fetchFn, not a URL string.**
    `String(request)` is `"[object Request]"`, so a mock routing on it matches nothing
    and every endpoint silently receives the same response - the summary tiles then
    rendered from the series payload with no error anywhere. Read `request.url`, and
    read headers off the Request rather than a non-existent `init` argument.
71. **PMD's `UnnecessaryCast` flags `(double) a / (double) b`, and removing both casts
    gives INTEGER division.** 70 hits in 100 requests would report a ratio of 0. The fix
    is neither a suppression nor one cast: a private helper taking two `double`
    parameters, so the longs widen at the call site and there is no cast to flag or to
    get wrong.
72. **A holder class with a private constructor and only nested types is a PMD finding,
    and PMD is right.** `MissingStaticMethodInNonInstantiatableClass`: a class that
    cannot be instantiated and offers nothing statically is a namespace pretending to be
    a type. Split the nested records into top-level files.
73. **A VU-based load test cannot observe saturation.** `ramping-vus` and
    `constant-vus` are CLOSED loops: each VU waits for its response before sending
    again, so when the system slows the test slows with it and offered load falls
    exactly when the system is struggling. The curve flattens smoothly and reads as a
    well-behaved system approaching its limit, when it shows the generator backing off.
    That is coordinated omission. Use `constant-arrival-rate`, and treat
    `dropped_iterations > 0` as "this step is not a valid data point" rather than as a
    measurement.
74. **`preAllocatedVUs` running out is indistinguishable from saturation.** k6 then
    drops iterations for its OWN reason, and the output looks exactly like the gateway
    failing to keep up. Over-allocate deliberately.
75. **The USL linearises exactly, so it needs no solver.** Dividing through by X gives
    `N/X = 1/lambda + (sigma/lambda)(N-1) + (kappa/lambda)N(N-1)`, which is ordinary
    least squares against three basis functions - one `numpy.linalg.lstsq` call, no
    scipy, no initial guess, deterministic. The catch worth reporting: minimising
    residuals in `N/X` is not minimising them in `X`, and the transform weights low-N
    points more heavily. Report BOTH R-squared values, and quote the one on throughput.
76. **An HPA silently invalidates a manual sweep.** It moves the replica count back
    between steps, so each step is measured at a pod count that is not the one recorded.
    `sweep.sh` refuses to start when one exists. And before measuring the HPA's own
    behaviour, confirm `kubectl get hpa` shows a number rather than `<unknown>` - see
    gotcha 48.
77. **A load test whose cache hit ratio drifts is measuring the cache, not the system.**
    Which tier answers IS the latency here: ~4 ms against 200 ms-30 s. Without a
    pre-warm before step 1, step 1 runs cold and step 6 warm, the ratio climbs across
    the sweep, and the fit describes a cache filling up. `fit.py` warns when the ratio
    moved more than 5 points across the steps.
78. **PITest does not honour surefire's excludes, and the failure mode INFLATES the
    score.** It discovers its own tests, so neither `<excludes>**/*IT.java</excludes>`
    nor `-DskipITs` reaches it - `skipITs` is a failsafe flag and PITest is not
    failsafe. Running a Testcontainers IT under PITest is not merely slow: PITest
    re-runs the suite per mutant, a 20-second container start blows the per-mutant
    timeout, and a timed-out mutant is scored `TIMED_OUT` which PITest counts as
    **DETECTED**. The score climbs while nothing detects anything. Fixed with
    `excludedTestClasses` in the root POM.
79. **`-Dtest=SomethingIT` makes surefire run an `*IT` class despite the exclude.** An
    explicit `-Dtest` overrides the configured `<includes>`/`<excludes>`, so the class
    ran twice in one build - once under surefire, once under failsafe. Harmless, but it
    means a one-off `-Dtest=` run is not the selection the real build makes, and a
    "passing" single-test run can prove less than it appears to.
80. **A hand-written `CREATE TABLE` in a test drifts from the migration, silently.** The
    first draft of `Tier2SemanticCacheIT` invented a `response_text` column; the real
    one is `response`. It would have passed happily against its own wrong schema while
    the production query was broken. Apply the real Flyway migration instead
    (`Flyway.configure().locations("classpath:db/migration")`, `baselineVersion("0")` -
    see gotcha on baselining). As a bonus that test became the only thing in the build
    proving the migration actually applies.
81. **`-pl <module> -am` with `-Dit.test=` fails on the PARENT.** The property applies to
    every project in the reactor, and the aggregator has no tests, so failsafe aborts
    with "No tests matching pattern". Needs `-Dfailsafe.failIfNoSpecifiedTests=false`
    in addition to the surefire one - they are separate properties.
82. **`secrets` is not an allowed context in an `if:`, and using it kills the ENTIRE
    workflow.** Not the step, not the job - GitHub refuses to validate the file, so
    zero jobs start. The run appears as **0 jobs, 0 seconds, `conclusion: failure`**,
    and is titled after the file PATH rather than its `name:` field. There is no log,
    because nothing ran. `yaml.safe_load` passes locally - the YAML is well-formed and
    it is the Actions schema that rejects it. **This killed CI for four consecutive
    runs across two days (#21-#24) while the repo went on claiming CI was green.** Map
    the secret into job-level `env:` and test `if: env.X != ''`. Guard with
    `python scripts/check-workflows.py` BEFORE pushing - and note why that cannot be a
    CI job: a workflow GitHub refuses cannot run the job that would have linted it.
83. **A step's own `env:` is not in scope for that step's `if:`, and the failure is
    silent.** `if: env.SONAR_TOKEN != ''` with `SONAR_TOKEN` mapped in the same step's
    `env:` reads empty, so the step is skipped **every time** and the job still reports
    success. A guard that always takes the skip branch is indistinguishable from a
    guard that works, which is worse than a crash: SonarCloud was recorded as "green
    in CI" in three documents and had never once run. Verified from the run #20 job
    data - "Build, test and analyse" skipped, "Report when Sonar is not configured"
    succeeded. The lesson generalises: **a skip branch that can never not be taken is
    a passing test of nothing.** Assert that the thing you configured actually ran.

84. **Coverage percentages are not comparable across runs without `clean`.** JaCoCo
    appends to `jacoco.exec`, AND its report only includes classes it actually analysed -
    so both the numerator and the DENOMINATOR move between runs depending on which
    classes got loaded. `common` read 66.1%, then 63.8%, then 66.1% again across three
    runs of identical code. Only a `clean verify` figure is quotable, and every number
    recorded here now comes from one.
85. **Spring Data normalises away Redis TTL sentinels.** `TTL` returns -1 for "no expiry"
    and -2 for "no such key", but `ReactiveRedisTemplate.getExpire` maps both to
    `Duration.ZERO`. A test asserting `isNegative()` on a persisted key therefore can
    never pass, however naturally it reads. It matters because `budget_record.lua` tests
    `TTL < 0` against the **raw** value, so the script and a Java-side helper genuinely
    see different things for the same key.
86. **A token budget can only be enforced check-before / record-after.** A completion's
    cost is not known until the completion exists, so there is no way to debit the right
    amount up front, and the overshoot is inherent. Reserving an estimate and reconciling
    afterwards bounds it but LEAKS a reservation whenever a request dies between the two
    writes, silently shrinking the tenant's budget until the period rolls over. A bounded
    overshoot that self-corrects beats an unbounded under-grant that does not - and the
    exact figure belongs in Postgres either way.

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
- [x] ArchUnit rules - `gateway/src/test/.../arch/ArchitectureRulesTest.java`, 18 rules
      (layering, no blocking calls, no static `now()`, `common` stays framework-free)

### ✅ P3 — Tier 1 + rate limiting (DONE, verified against real Redis)
- [x] `Tier1Cache`: reactive Redis exact-hash, never a hard dependency
- [x] `token_bucket.lua`: atomic, Redis server clock, documented
- [x] `TokenBucketRateLimiter` with fail-open/fail-closed policy
- [x] **Per-tenant token budgets - BUILT (2026-11-09).** `BudgetExhausted` was
      previously constructed **only in tests**: the API advertised a 402 the gateway was
      structurally incapable of returning. Now in `gateway/.../budget/`:
      `budget_record.lua` (atomic INCRBY+EXPIRE), `BudgetPeriod` (DAILY/MONTHLY, UTC),
      `BudgetProperties`, `BudgetDecision`, `TenantQuotaRegistry`, `TokenBudgetLedger`,
      `BudgetGate`, `BudgetConfig`. Wired into `CompletionPipeline` after the rate limiter
      and **before the cache** - a cache hit spends quota, so checking later would serve
      an exhausted tenant every cached answer free
- [x] **49 tests, budget package at 100% line coverage.** `BudgetGateTest` (16),
      `BudgetPeriodTest` (12), `BudgetPropertiesTest` (6), **`TokenBudgetLedgerIT` (24)
      against real Redis 7.4** - including the three degraded paths
- [x] Ships **off by default with shadow mode on**, the same two-stage roll-out as the
      semantic cache: enabling it turns previously-successful requests into 402s, and
      `gateway_budget_shadow_would_have_refused_total` is what justifies the switch
> **Property, not a task** (gotcha 86). The budget overshoot is inherent: a
> completion's cost is unknown until the completion exists, so enforcement is
> check-before / record-after and a tenant near its limit can exceed it by roughly
> (concurrent requests x tokens each). `usage_ledger` stays exact and authoritative; the
> Redis counter is an enforcement figure allowed to be approximate at the edges.
- [x] **Streamed responses now draw down the budget** - deltas are counted as they
      pass (lengths summed, content never retained) and converted by `TokenEstimator` at
      4 chars/token, rounding UP so the estimate errs toward the limit.
      `doFinally`, not `doOnComplete`: a client that disconnects mid-stream has still
      consumed what was delivered, and recording only on completion would let a caller
      avoid the drawdown by hanging up. `gateway.budget.estimated_tokens` makes the
      estimated share measurable
- [x] **Streaming now uses the provider's REAL token count when it is available.**
      Requests send `stream_options.include_usage`; the terminal usage frame is parsed and
      carried back through the **Reactor Context** (`StreamUsage`) rather than by threading
      a sink through the registry and the resilience decorator - Context already propagates
      subscriber-to-source, which is the direction needed
- [ ] The estimate remains the FALLBACK, for providers that send no usage frame - Ollama,
      the mock, a vendor ignoring the option, or a stream that ended early. Absence stays
      distinguishable from zero on purpose. CJK under-counts at 4 chars/token, stated in
      `TokenEstimator` rather than papered over with a bigger table of constants
- [x] **Per-tenant limits are published** - `control-plane/.../quota/TenantQuotaPublisher`
      writes `tenants.quota_tokens` to `{tenant:x}:quota`, 9 tests. A periodic reconcile
      rather than only an event: Redis has no durability guarantee here, so a restart,
      eviction or failover would silently revert a tenant to the default. Keys carry a TTL
      longer than the reconcile interval, so a DELETED tenant loses its entitlement on its
      own rather than needing an explicit delete that could be missed
- [x] The key shape is pinned on **both** sides. The two services build it by separate
      string concatenation, and a mismatch would fail nothing loudly - the gateway would
      never find a value and use the default forever, which is the exact bug this closes
- [x] **`token_bucket.lua` now runs against real Redis.** `TokenBucketRateLimiterIT`,
      13 tests, `ratelimit` package at **100% line coverage**. `TokenBucketRateLimiterTest`
      mocks `redis.execute` outright, so it only ever asserted which ARGUMENTS the limiter
      passes - every claim the script makes was untested
- [x] **The test that matters: 200 simultaneous acquires against a capacity-10 bucket let
      through exactly 10.** That is the first automated evidence the script is atomic,
      which is the only reason it is a script: the naive GET/compute/SET is a
      read-modify-write across a network round trip, so two replicas serving one tenant
      both read the same count and both proceed, making the effective limit
      (configured limit x replica count). A second test asserts the 20 allowed acquires
      report 20 DISTINCT remaining counts - a lost update is then visible even when the
      total happens to come out right
- [x] Also pinned against real Redis: a first request starts the bucket FULL (starting
      empty would 429 a tenant's very first call and read as an outage); refill is
      computed from Redis's own `TIME` and observed over real elapsed time; tokens are
      clamped to capacity so an idle tenant cannot bank an hour's allowance into one
      burst; the bucket carries a TTL; an expired bucket is recreated full; a refused
      over-capacity acquire does NOT debit the bucket; and tenant isolation
- [x] **`Tier1CacheIT`, 11 tests against real Redis.** All three Redis components now
      have real-server coverage. Covers what a mock cannot: the TTL is really applied and
      really expires, the entry survives a real string codec and JSON round trip
      (`Instant` included), and the `{tenant:x}` hash tag lands in the live keyspace.
      One assertion of mine was wrong and the code right: a hit reports `ProviderId.CACHE`,
      not the original upstream - deliberate and consistent with Tier 2, since no vendor
      was called. The original survives in the stored entry; the test pins both halves

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
- [x] Testcontainers integration test for Tier 2 - `Tier2SemanticCacheIT`, 18 tests
      against real pgvector, passing locally AND in CI (see P11)
- [x] TTL eviction sweep - delivered in the `indexer` service in P6
      (`gateway.indexer.eviction-enabled`), not in the gateway

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

### ✅ P10 — Observability (DONE, **verified live against the cluster 2026-11-10**)

Verified end to end with the `metrics` profile, in this order, because each step
can pass while the next one silently does not:

| Check | Result |
|---|---|
| Prometheus + Grafana + kube-state-metrics + adapter | 7/7 pods Running |
| Gateway `ServiceMonitor` created by our chart | `serviceMonitor/default/aigw-ai-gateway-gateway/0` |
| Prometheus scraping the gateway | target `health=up`, no error |
| Gateway metrics stored | 478 series, 21 `gateway_*` metric names |
| Dashboards structurally valid | 29 panels, ids unique, no overlap |
| Every metric the dashboards query exists | 30 expressions, all resolve |
| Dashboards loaded INTO Grafana by the sidecar | `ai-gw-cache`, `ai-gw-red` |
| Prometheus is Grafana's default datasource | yes |
| Custom metrics API **serving a value** | `gateway_requests_per_second` returns a number |
| HPA calculating from the custom metric | `ScalingActive=True, ValidMetricFound` |

The last two are the ones that matter and the easiest to fake. The custom
metrics API being *registered* is not the same as it *serving a value*, and an
HPA whose metric is unavailable reports `<unknown>` and silently falls back to
CPU - which on an I/O-bound gateway yields a flat replica count that reads as
evidence it does not need to scale. The recorded condition is
`ValidMetricFound ... from pods metric gateway_requests_per_second`, with
`83339m` (83.3 rps/pod) observed under load, so it is scaling on RPS and not on
the CPU fallback.

**Three defects found by running it** (all fixed, see git log):

1. `monitoring.serviceMonitor.enabled` defaulted to **false**, so the chart
   shipped a ServiceMonitor that no deployment ever created. Prometheus was
   scraping nothing of ours.
2. Grafana 13 was OOMKilled in a loop at the configured 192 Mi - it now runs an
   embedded apiserver - and separately needed more than the chart's 60 s + 10
   liveness failures to bind port 3000. Both show as
   `connect: connection refused`; only `lastState.terminated.reason` separates
   `OOMKilled/137` from `Error/1`.
3. `kubectl scale`/`kubectl patch` during a load sweep take server-side-apply
   ownership of fields the chart also manages, after which `helm upgrade` fails
   with a field-manager conflict. `--take-ownership` does not help (it only
   covers Helm's own annotations); the release has to be reinstalled.

Original code-complete notes follow.
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

### 🟡 P11 — Testing and quality (gateway 89.5%; mutation run wired to CI but NEVER EXECUTED)
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
- [x] **SonarCloud now runs, and the analysis is real** (2026-11-09). Quality Gate
      PASSED with 3 new issues to review:
      https://sonarcloud.io/dashboard?id=VasanthvasanthM_multi-tenant-ai-gateway
- [x] Getting here took fixing TWO independent causes, and only one was a code defect:
      1. The step was gated on `if: env.SONAR_TOKEN != ''` with the secret mapped in that
         SAME step's `env:`, which is not in scope for its own `if`. The condition read
         empty, so the analysis was skipped every time while the job reported success
         (gotcha 83). Fixed by moving the mapping to job level.
      2. `SONAR_TOKEN` was simply not set. The user added it plus `SONAR_ORGANIZATION`
         and `SONAR_PROJECT_KEY` through the GitHub UI.
- [x] **Verified the right way.** A green SonarCloud JOB proves nothing here - that is
      exactly how this failed silently for months. What proves it is SonarCloud's own
      check run reporting "Quality Gate passed" with an issue count, and the step named
      "Build, test and analyse" reporting `success` rather than `skipped`. Both checked
- [x] The job exited 1 while the analysis succeeded, because SonarCloud's **Automatic
      Analysis** was running alongside the CI analysis and `sonar:sonar` refuses that
      combination. Turned off in the project's Analysis Method settings; run #34 is green
      with "Build, test and analyse" reporting `success`
- [x] **All 3 issues fixed, and one was a real bug I had just introduced:**
      1. **BLOCKER, `ApiKeyAuthFilter`** - two constructors and neither annotated. Adding
         the 2-arg overload so the existing tests kept compiling left Spring unable to
         choose, with no no-arg fallback, so the gateway CONTEXT WOULD FAIL TO START. The
         tests never caught it because they construct the filter by hand. Same class as
         the `ShardProperties` binding bug earlier the same day - made twice. Fixed with
         `@Autowired` on the three-argument constructor
      2. **BLOCKER, `CacheKey`** - a static `HEX` beside the record component `hex`, in
         the one class where a mistake is a security incident. Renamed `HEX_FORMAT`
      3. **MAJOR, `PromptNormalizer`** - `^A+|A+$` parses correctly and reads
         ambiguously, since alternation binds loosest. Grouped explicitly
- [x] **This is the argument for having wired Sonar up at all.** Its first run found a
      startup defect that 622 passing tests did not, because every test constructed the
      object by hand and none started the context
- [x] **`Tier2SemanticCache`: 100 missed lines -> 3. 96.9% line / 87.5% branch.**
      `Tier2SemanticCacheIT`, **18 tests, all passing against real pgvector 0.8.7 / PG17**
      - actually run locally, not merely compiled. Moved the gateway module from 80.7% to
      **89.5% line / 87.5% branch**
- [x] The IT applies the **real** `V1__semantic_cache.sql` through the Flyway Java API
      rather than a hand-written `CREATE TABLE` (gotcha 80), so it is also the only thing
      in the build that proves that migration applies. No Spring context - the cache is
      built directly over `ShardedDatabase.single(...)`, which avoids dragging in Redis
      and Kafka to test a class that touches neither
- [x] What it pins, beyond coverage: the **`<#>` operator sign** (three entries at known
      angles, so a dropped negation returns the farthest row and the test says so);
      **tenant isolation** against an identical vector; model and embedding-model
      isolation; expiry; shadow mode and the non-semantic suppression both recording
      without serving; **exactly one `gateway.cache.lookup` outcome per lookup**; that a
      database failure counts as `error` and NOT as `miss`, and degrades to a miss rather
      than a failed request; and the schema itself - `vector_ip_ops` on the HNSW index
      and `HASH (tenant_id)` partitioning into 8 partitions
- [x] **PITest wired into both CI systems** - a `mutation` job in
      `.github/workflows/ci.yml` and a `Mutation testing` stage in the `Jenkinsfile`,
      main-only, advisory on its first runs exactly as `coverage-gate` is
- [x] `scripts/pit-summary.py` - turns `mutations.xml` into the score per module plus
      **the classes with the most survivors**, which is the actionable half PITest's own
      output omits. Reports `SURVIVED` separately from `NO_COVERAGE`: they score the same
      and mean different things - no assertion versus no test. Verified against a
      synthetic report, including both failure paths
- [x] `excludedTestClasses` added to the POM - without it PITest runs the ITs and the
      score goes UP for the wrong reason (gotcha 78, the nastiest one in this phase)
- [x] **The mutation run EXECUTED for the first time** in CI run #26 (2026-11-08). The
      `Summarise` step succeeded and a 115 KB `mutation-report` artifact was uploaded, so
      PITest produced real results across all four modules
- [x] **The mutation score is read: `common` 51.7%** (187 of 362 killed), fetched with
      `gh` after the user authenticated it - the token lives in the OS keyring and never
      went through the chat
- [x] **The job was hiding three modules.** PITest fails a module below its threshold and
      Maven then stops the reactor, so the first module under 70% meant `embedding`,
      `kafka-support` and `gateway` were never measured at all - which read as "no
      mutants" rather than "never measured". Fixed with `--fail-at-end`
- [x] **`MurmurHash3` had 51 surviving mutants, the worst finding in the repository.**
      The existing tests checked properties - determinism, distribution, avalanche - and
      pinned NO actual output. Every constant, shift and mix step could change with the
      suite green. That function decides which shard every tenant lands on, so a silent
      change remaps every tenant at once and orphans their entire Tier-2 cache: rows
      still there, nothing errors, hit ratio collapses for reasons no log explains.
      `MurmurHash3KnownAnswerTest` now pins 64 vectors across 4 seeds plus a 1 KiB input
      that exercises the block loop rather than only the tail
- [x] `MutationGapsTest` closes the rest: validation BOUNDARIES (the tests passed values
      well inside each range, so PITest could move `<` to `<=` unnoticed),
      `TokenUsage.total` arithmetic, `ConsistentHashRing` sort order and replica count,
      `ShardRouter.isSingleShard` false case, `SubShard` range ends. `common` goes from
      129 tests to 213
- [x] Two of my own assertions were wrong and the code was right: `topP` is `(0, 1]` not
      `[0, 1]` - zero top-p admits no tokens, so it is correctly refused - and an empty
      ring is refused at ROUTE time rather than construction, since a deployment may
      start with no shards configured
- [ ] Re-measure after these tests. The 51.7% figure predates them, and the other three
      modules have still never been measured - the `--fail-at-end` fix lands with this
      commit but has not run yet
- [ ] PITest still cannot run locally: its engine jars are not in `~/.m2` and the online
      fetch was declined. CI is now the only place it runs
- [x] Verify-phase figures, from a **clean** build (gotcha 84), 2026-11-09:
      **gateway 90.5% line / 88.5% branch**, common 66.1% / 71.6%, embedding
      60.7% / 64.1%, kafka-support 25.3% / 31.2%. **337 unit tests + 55 ITs**, all
      passing. The `budget` and `ratelimit` packages are both at 100% line coverage.
      Only `gateway` clears the 80/70 gate, so `coverage-gate` stays advisory and
      `-Pquality` still fails at `common` - the known state, not a regression
- [x] **Both 0% classes now tested.** `CompletionHandlerTest` (22) through a real
      router and HTTP exchange, and `SqlUsageQueryServiceIT` (14) against real Postgres
- [x] **The handler test found a real bug**: `messages` carried `@NotEmpty` but not
      `@Valid`, and Bean Validation does not descend into a collection without it - so
      `MessageDto`'s `@NotBlank` constraints on role and content were DEAD CODE. A blank
      role passed validation and then threw `IllegalArgumentException("unsupported role")`
      from inside `toDomain()`. The caller still got a 400, but only because the handler
      of last resort catches `IllegalArgumentException`, and the message leaked
      domain-layer wording instead of naming the field. Fixed; the test asserts on
      `messages[0].role` so the cascade cannot lapse again
- [x] Also corrected a comment that described the opposite of the behaviour: the handler
      claimed an empty body "deserialises to null rather than failing". It does not - a
      zero-length body is refused by the CODEC first (`ServerWebInputException`). The
      `switchIfEmpty` guard is reached by a body that PARSES to nothing, a literal JSON
      `null`. There is now a test for each path
- [x] `SqlUsageQueryServiceIT` applies **metering's** migration from the filesystem rather
      than a copy of the DDL - `tenant_usage_rollup` is owned by metering and read-only
      here, and a hand-written copy is exactly where a cross-service read drifts. It also
      pins a hazard only a real driver shows: `sum()` over `BIGINT` returns `numeric`, so
      every aggregate arrives as a `BigDecimal`
- [x] **All four modules now have unit tests** - control-plane 20 (`SagaContextTest` 8,
      `SagaOrchestratorTest` 12), metering 9, audit 7, indexer 6. Chosen for what an IT
      cannot reach: compensation ORDER and which steps count as completed; that a DUPLICATE
      usage event must not touch the rollup (Kafka redelivers on every rebalance, and a
      rollup that drifted upward from the ledger is what the invoice reads); that a null
      audit field becomes EMPTY and never a sentinel like "unknown", which in an export is
      indistinguishable from a value someone supplied; and that the eviction sweep stops on
      the first empty batch, is capped at 20, and never lets an exception escape a
      `@Scheduled` method - which in Spring cancels the schedule permanently
- [x] `SagaOrchestratorTest` also pins a genuine Reactor subtlety: `then(markState(...))`
      evaluates its argument at ASSEMBLY time, so the terminal UPDATE is assembled before
      the INSERT is subscribed. Harmless, because the Mono is cold - but it is the same
      shape as the bug `CompletionPipeline` guards against with `Mono.defer`, and the test
      measures EXECUTION order so anyone adding a side effect there finds out why
> **Note.** The mutation run stays scoped to the four modules that have unit tests.
> PITest excludes ITs, so mutating `control-plane`, `metering`, `indexer` and `audit`
> would once have scored every mutant against the absence of a test. They now have unit
> tests, so widening the scope is a reasonable next step - but it will lengthen the run.

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
- [x] **Whale-tenant sub-sharding BUILT** - `common/.../shard/SubShard.java` (keys and
      FNV-1a bucket derivation, 13 tests), `whale-buckets` config, and whale-aware routing
      on `ShardedDatabase` (`forWriteBucket`, `readClientsFor`, 6 tests). Opt-in per
      tenant, minimum 2 buckets, and it logs a WARN on startup when configured
- [x] **The design as written contained a trap, now resolved.** The HLD prescribed
      `tenantId:bucket(promptHash)`, but splitting a tenant's vectors by prompt hash
      BREAKS Tier 2: a semantic search looks for SIMILAR prompts, and similar prompts hash
      to unrelated buckets, so routing a lookup to one bucket would silently find only the
      fraction of that tenant's history which landed there - a mysteriously poor hit ratio
      for the biggest tenant. So a whale's Tier-2 reads FAN OUT across its buckets and the
      best candidate wins. That is the cross-shard ANN search ADR-0006 otherwise refuses,
      accepted here only because the fan-out is bounded by one tenant's bucket count
      rather than the shard total, and because the alternative is a hot shard that bounds
      everyone. Exact-hash (Tier 1) is unaffected - the same prompt always derives the
      same bucket
- [x] **`recordHit` is now bucketed.** The nearest-neighbour query returns the row's own
      `cache_key`, and writes bucket on exactly that value, so the update lands on the
      shard the row lives on. Routing on the tenant alone sent it to the base shard where
      it matched nothing, leaving `hit_count` at zero for most of a whale's rows - which is
      what the indexer's evict-by-unpopularity ordering reads
> **Superseded.** `ShardRouter.route(String)` accepted a `tenant#bucket` key and
> nothing computed those buckets. `SubShard` now does, and `ShardedDatabase` routes on
> it - see the two items above.
- [x] **Replica-lag alerting BUILT** - `ReplicaLagMonitor` probes each shard's READ
      client every 30s via `pg_last_xact_replay_timestamp()` and publishes
      `gateway_replica_lag_seconds`, tagged by shard and by whether that shard actually
      has a replica (a single-node deployment otherwise reports a constant zero that looks
      exactly like a healthy replica). 7 tests. `Flux.interval` on `boundedElastic` rather
      than `@Scheduled`, so no scheduler thread pool is added to a WebFlux data plane
- [x] `deploy/k8s/observability/alerts/replica-lag.yaml` - the repo's FIRST alert rules.
      Two on lag (warning at 5s, critical at 60s) plus two that make those trustworthy: a
      failed probe leaves the gauge holding its LAST value, so a broken probe presents as
      a flat line under the threshold - identical to health. Hence a separate alert on
      `gateway_replica_lag_probe_failures_total`, and an `absent()` rule for the series
      vanishing, which no threshold can match. ADR-0007 updated; its "not yet built"
      paragraph was the stale claim this discharges
- [x] **A lagging shard's reads now fail over to its primary**, past
      `gateway.sharding.max-replica-lag` (default 30s, between the warning and critical
      alerts; zero disables it for a deployment whose primary cannot absorb the read load).
      Fails BACK on recovery, so one slow minute does not strand a shard until restart.
      `gateway_replica_read_failovers_total` marks the moment load MOVED, which is what
      explains a step change in primary CPU. 14 tests
- [x] **A stale gauge reads as HEALTHY here, deliberately.** Treating "unknown" as
      unhealthy would move a whole shard's reads onto its primary because MONITORING broke
      - a bigger outage than the one it guards against. The probe-failure counter is what
      makes that state visible instead
> **Superseded.** The concern was that unbounded lag silently degrades the hit rate -
> a cost regression with no error anywhere, visible only as a falling hit ratio, which is
> the symptom rather than the cause. `ReplicaLagMonitor` and the alert rules now measure
> the cause directly.
> **Deliberate, not a task.** Helm does not express a shard topology: a list of shards
> is awkward as environment variables, and there is no multi-database deployment to point
> it at on this hardware.

### ✅ P9 — Terraform (DONE; AWS is validate-only by design)
- [x] **`terraform/local/`** - Helm releases against the k3d cluster: MetalLB,
      ingress-nginx with the pinned NodePorts, kube-prometheus-stack,
      prometheus-adapter and the application chart. Three independent `install_*`
      flags rather than one profile string, because the combinations are genuinely
      used (metrics without the app, app without metrics)
- [x] The script/Terraform split is deliberate and documented in `main.tf`:
      **the script owns the cluster and the MetalLB address pool, Terraform owns the
      releases.** k3d has no provider, and wrapping `k3d cluster create` in a
      `null_resource` gives Terraform a resource it cannot read or diff - so `plan`
      would lie. The address pool needs the live Docker subnet plus a webhook-readiness
      retry, which Terraform expresses badly and `cluster-up.sh` already does
- [x] `kube_context` has a `validation` block requiring a `k3d-` prefix, so a stray
      KUBECONFIG cannot point a MetalLB + NodePort install at a managed cluster
- [x] Chart versions are variables whose defaults MATCH the scripts - two sources of
      truth is how a Terraform apply silently downgrades what a script installed
- [x] `wait = false` on the application release: it needs Redis and PostgreSQL, which
      this configuration does not deploy, so waiting would block for the full timeout
      and then fail on a readiness probe that is correctly reporting a missing
      dependency
- [x] **`terraform/aws/`** - EKS 1.33 (two node groups), Aurora PostgreSQL Serverless
      v2 with pgvector in `shared_preload_libraries`, ElastiCache Redis in cluster
      mode, MSK Serverless with SASL/IAM, 3 AZs, IRSA scoped to one service account
- [x] **Three-tier subnets, and the data tier has NO NAT route at all** - so Aurora,
      ElastiCache and MSK cannot reach the internet, enforced by a missing route rather
      than by a rule someone can widen. Their security groups also have no egress rules
- [x] Plain resources, no third-party modules: for something whose purpose is to be
      READ, `terraform-aws-modules/eks` would hide the decisions behind its defaults
- [x] **Costed.** ~$3,500/month idle, $25-30k at the modelled peak. The useful number
      is the comparison: at peak the whole stack is **~0.2% of the provider spend the
      cache avoids** ($192M/yr from scaling-model.md), and a 1% hit-rate improvement is
      worth more than the entire annual infrastructure bill at minimum capacity
- [x] **tflint + checkov wired into CI**, with `.tflint.hcl` and `.checkov.yaml`
- [x] **Security review done properly: 10 checkov findings became 0.** Five were FIXED
      (auto minor upgrades, snapshot tag copying, `log_statement = ddl`, explicit KMS
      key policies with `kms:ViaService` conditions, and locking down the VPC default
      security group); five are suppressed with a written reason each, and the three
      that are genuine gaps are listed in `terraform/aws/README.md` rather than hidden
- [x] Provider locks committed for **both** linux_amd64 and windows_amd64 (gotcha 64)
- [x] Verified locally, not just in CI: `fmt -check`, `validate` and tflint clean for
      both directories, checkov **176 passed / 0 failed**

**Never applied, and that is the design.** No AWS credentials exist in the repository
or the pipeline. `validate` does not contact AWS, which is exactly why the whole thing
can be checked by a pipeline with no AWS account. Two consequences, noted at the
variables that declare them: nothing verifies that `kubernetes_version` or
`aurora_engine_version` actually exist, or that the instance types are available in the
region.

> **Correct behaviour, not a task.** Infracost is wired in and skips without
> `INFRACOST_API_KEY`, which is what keeps a fork from breaking on a credential it cannot
> have. The consequence is real and stated: the cost table in the README is hand-computed
> and unverified.

### 🟡 P12 — Load testing (tooling DONE and verified; **campaign NOT run**)
- [x] `loadtest/k6/completions.js` - one sweep step, **`constant-arrival-rate`**
      (gotcha 73). `dropped_iterations` is a threshold, and a step that drops any is
      EXCLUDED from the fit rather than recorded
- [x] `loadtest/k6/lib/corpus.js` - the hit ratio is an INPUT, not an accident: 55%
      exact repeats / 15% paraphrases / 30% unique, targeting the ~70% the economics in
      scaling-model.md assume. Prompts kept under 1,000 characters so the run does not
      silently measure the Tier-2 skip path
- [x] `loadtest/scripts/sweep.sh` - scales 1->6, pre-warms ONCE before step 1
      (gotcha 77), settles after Ready rather than instead of it, pins k6 to its own
      cores, refuses to run when an HPA exists (gotcha 76), and excludes any step that
      dropped iterations or saw a 429
- [x] `loadtest/analysis/usl.py` - the fit. Linearises exactly, so one
      `numpy.linalg.lstsq` call and no scipy (gotcha 75). Reports BOTH R-squared
      values; `N_max` returns None rather than a huge number when kappa is ~0
- [x] `loadtest/analysis/fit.py` - CSV to markdown, printing **"Reasons to distrust
      this fit" BEFORE the coefficients**, and exiting non-zero below R-squared 0.95 so
      a pipeline cannot publish an untrustworthy fit silently
- [x] **`loadtest/analysis/test_usl.py` - 13 tests, all passing.** The central one
      generates throughput from chosen coefficients and asserts the fitter returns
      them: lambda=1200, sigma=0.035, kappa=0.00012 recovered to 1e-6, across three
      parameter shapes. A fitting routine that cannot do that cannot be trusted, and
      plausible output on real data would never reveal it
- [x] Both verifications wired into CI - the USL tests and `node --check` on the k6
      scripts. The campaign needs a cluster; the thing that turns measurements into a
      conclusion does not, and it is the part where a silent error would be worst
- [x] `loadtest/README.md` - the protocol, and the four ways to produce a confident
      wrong answer that it guards against

**NOT RUN, and nothing here pretends otherwise.** k6 is not installed and the cluster
needs ~2 GB against ~460 MB available. Every throughput figure in
`docs/architecture/scaling-model.md` is still labelled **(C) calculated**; there is no
measured throughput number anywhere in the repository.

- [ ] Run it. One command once a cluster exists:
      `./scripts/cluster-up.sh && ./scripts/observability-up.sh && ./loadtest/scripts/sweep.sh`
- [ ] Fold the measured lambda, sigma, kappa and N_max into scaling-model.md, replacing
      the sensitivity table with the real curve

### ✅ P14 — Dashboard (DONE, with one stated gap)

**Backend - a tenant usage API, because the dashboard needed one.**
- [x] `GET /v1/usage/summary` and `GET /v1/usage/series`, authenticated by the same API
      key as the completions endpoint. **The tenant comes from the key, never from a
      parameter** - there is deliberately no `?tenant=`, which would make a cross-tenant
      read a URL away and look like a feature
- [x] Reads `tenant_usage_rollup` through `ShardedDatabase.forRead()`, so it follows the
      same shard key and replica preference as Tier 2. A dashboard query is exactly the
      workload a read replica is for
- [x] A cross-service read, stated rather than hidden: metering OWNS that table. The
      alternative was a second authenticated HTTP surface on metering, judged worse -
      the gateway is already the only service tenants authenticate against, and a second
      tenant-facing edge is a second place to get key handling and tenant resolution
      right
- [x] `tokens_saved` = metered tokens minus tokens that reached a provider, from two
      independently recorded columns rather than inferred from the hit ratio - a hit on a
      long prompt saves far more than a hit on a short one
- [x] `estimated_saving_usd` is OMITTED when no price is configured. `UsagePricing` uses
      a boxed `Double` so "unset" and "explicitly zero" are distinguishable: a
      self-hosted model has no per-token cost, so a money figure there is meaningless
      rather than unknown
- [x] Window clamped to 90 days server-side - `?hours=1e9` would otherwise be a table
      scan anyone can request. Over-large is CLAMPED, malformed is a 400: `?hours=undefined`
      is what a client bug sends, and defaulting would render 24 hours of data under a
      label claiming something else
- [x] 29 tests. `UsageQueryService` is an interface so the handler needs no
      `DatabaseClient` stubbing - which is the thing that left `Tier2SemanticCache`
      uncovered
- [x] **Gateway coverage held at 80.4%** with 127 new lines of production code

**Frontend - React 19 + TypeScript + Vite + RTK Query + Recharts.**
- [x] 37 tests, all passing; `tsc --noEmit` clean under every strict flag including
      `exactOptionalPropertyTypes` and `noUncheckedIndexedAccess`; ESLint clean
- [x] **Charts code-split**: Recharts pulls in d3 and took the entry bundle to 684 kB
      for a page whose most important content is four numbers. Now 314 kB (100 kB
      gzipped), and CI asserts the budget - an accidental static import would double it
      and nothing else would report that
- [x] Chart colours **validated, not chosen** - both light and dark sets pass every
      check against their own surface. Colour tracks the ENTITY (cache always aqua,
      provider always orange) rather than series order, which repaints survivors when a
      filter removes one
- [x] A table view, which is **required rather than optional**: light-mode aqua measures
      2.74:1 against the surface, below the 3:1 floor, and the documented relief is a
      visible label or a table. It also answers "what was the number at 14:00", which a
      line chart makes you estimate
- [x] No dual-axis chart anywhere; the ratio axis is fixed to 0-1 so a chart ranging
      0.68-0.71 does not look like a catastrophe
- [x] Retry offered only when the server's `retryable` flag says it would help
- [x] CI job: typecheck, lint, test, build, bundle budget

**The gap, stated rather than glossed:**
- [x] **The API key is no longer compiled into the bundle.** `VITE_API_KEY` is inlined
      by Vite and readable in every visitor's devtools. Replaced by a session exchange: a
      human pastes the key once, the gateway verifies it and returns an opaque 256-bit id
      in an **HttpOnly, SameSite=Strict** cookie, so script never holds the credential and
      an XSS cannot exfiltrate one. The cookie authenticates **read-only usage endpoints
      only** - it cannot reach `/v1/chat/completions`, so a stolen cookie cannot spend
      money, and that bound is also what makes SameSite=Strict sufficient without a CSRF
      token. `Secure` is derived from `X-Forwarded-Proto`, not hard-coded, or the cookie
      would be silently undeliverable over plain HTTP in local development.
      `DashboardSessions` + `SessionHandler`; `VITE_API_KEY` still works for a developer
      against their own gateway and must not be set in a hosted build

**OpenAPI:** the two new endpoints are documented, and so are `X-Cache-Tier` and
`X-Provider` - two response headers the gateway has always sent and the spec never
mentioned. Found by reading `CompletionHandler` while wiring the dashboard.

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

All four original items are DONE: `gh`/`docker` auth, the prune, and the first commit.
Images are published and `main` is pushed. What is outstanding now:

- [ ] **Add `SONAR_TOKEN`** (secret) plus `SONAR_ORGANIZATION` and `SONAR_PROJECT_KEY`
      (variables) in GitHub **Settings -> Secrets and variables -> Actions**, in a
      browser. This is the ONLY thing between the repo and the SonarQube analysis that
      was asked for in the original brief. SonarCloud is free for public repositories.
      Never paste a token into the chat (gotcha 62)
- [ ] **Read the mutation score** from the run summary and paste it back. It cannot be
      fetched from here: job logs are 403 and the artifact 401 without authentication.
      Installing GitHub CLI and running `gh auth login` would remove this limitation
      permanently - the token goes to the OS keychain, never through the transcript
- [ ] **~2 GB of free RAM, plus `k3d` and `k6`**, for the cluster and the load campaign
      (P8/P10/P12). Measured 269 MB free on 2026-11-08 with Docker Desktop running.
      This is the single blocker holding three phases, and no amount of code moves it
- [ ] Optional: a free-tier LLM API key (Groq / OpenRouter) if a real provider demo is
      wanted. `MockLlmProvider` is the deliberate default (ADR-0011)

---

## 7. Conventions

- Java package root: `io.vasanth.gateway`
- Module = service, except `common` and `embedding` which are libraries
- Tests: `*Test.java` = unit (surefire), `*IT.java` = integration (failsafe, Testcontainers)
- Every architectural decision gets an ADR in `docs/adr/NNNN-title.md`
- No Lombok — Java 21 records and sealed interfaces instead
- **Update this file at the end of every work session.**
