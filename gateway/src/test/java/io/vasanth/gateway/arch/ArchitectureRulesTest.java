package io.vasanth.gateway.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural invariants, enforced at build time.
 *
 * <p>These exist because the rules they encode are invisible in review. Nobody notices a
 * {@code .block()} in a diff; everybody notices it at 3am when four event loops are parked
 * and the service has stopped answering. A failing test at compile time is the cheapest
 * place to catch them.
 */
@AnalyzeClasses(
        packages = "io.vasanth.gateway",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // =====================================================================
    // Reactive discipline. The expensive-to-find bugs.
    // =====================================================================

    /**
     * The single most damaging mistake available in a WebFlux application.
     *
     * <p>{@code block()} on an event loop thread parks it. With four cores there are four
     * event loops, so a handful of concurrent blocking calls freezes the entire gateway -
     * including the cache hits that needed no I/O at all. It does not fail, it does not
     * log, it simply stops answering.
     *
     * <p>This rule was added after exactly that mistake was written into
     * {@code ProviderRegistry} and caught on review rather than by a test.
     */
    @ArchTest
    static final ArchRule noBlockingOnReactiveTypes = noClasses()
            .should().callMethodWhere(
                    com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                            com.tngtech.archunit.core.domain.properties.HasName.Predicates
                                    .nameMatching("block|blockFirst|blockLast|blockOptional"))
                            .and(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                    com.tngtech.archunit.core.domain.properties.HasOwner
                                            .Predicates.With.owner(
                                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                                    "Reactor publisher",
                                                    owner -> owner.getName()
                                                            .startsWith("reactor.core.publisher."))))))
            .because("block() on a Netty event loop stalls every request sharing that loop; "
                    + "use subscribeOn with a bounded scheduler instead");

    @ArchTest
    static final ArchRule noThreadSleep = noClasses()
            .should().callMethod(Thread.class, "sleep", long.class)
            .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
            .because("Thread.sleep on an event loop blocks it; use Mono.delay or "
                    + "delaySubscription, which yield instead of parking the thread");

    @ArchTest
    static final ArchRule noDirectThreadCreation = noClasses()
            .should().callConstructor(Thread.class)
            .because("threads must come from a bounded Reactor scheduler, so that "
                    + "concurrency stays observable and capped");

    // =====================================================================
    // Determinism and testability
    // =====================================================================

    /**
     * Time must be injected.
     *
     * <p>Code that calls {@code Instant.now()} directly cannot be tested for TTL, expiry
     * or staleness behaviour without sleeping, and tests that sleep are slow and flaky.
     * Every timestamp in this service comes from an injected {@link java.time.Clock}.
     *
     * <p>{@code System.nanoTime()} is deliberately permitted: it measures elapsed duration
     * rather than wall-clock time, and is not something a test needs to control.
     */
    @ArchTest
    static final ArchRule noStaticNowCalls = noClasses()
            .that().resideOutsideOfPackage("..config..")
            .should().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(java.time.LocalDateTime.class, "now")
            .orShould().callMethod(java.time.LocalDate.class, "now")
            .because("timestamps must come from an injected Clock, or TTL and expiry "
                    + "behaviour cannot be tested without sleeping");

    @ArchTest
    static final ArchRule noSystemOut = noClasses()
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .because("console writes bypass the structured logger, so they carry no trace "
                    + "id and never reach the log aggregator");

    // =====================================================================
    // Layering
    // =====================================================================

    /**
     * Layers are pinned to exact packages, not globs.
     *
     * <p>The first version used {@code "..cache.."}, which also matched
     * {@code io.vasanth.gateway.common.cache} - the shared domain package holding
     * {@code CacheTier}. Every provider legitimately references that enum, so the rule
     * reported five violations that were not violations. A loose glob in an architecture
     * rule is worse than no rule: it trains people to ignore the output.
     */
    @ArchTest
    static final ArchRule layering = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("io.vasanth.gateway.web..")
            .layer("Pipeline").definedBy("io.vasanth.gateway.pipeline..")
            .layer("Cache").definedBy("io.vasanth.gateway.cache..")
            .layer("Provider").definedBy("io.vasanth.gateway.provider..")
            .layer("RateLimit").definedBy("io.vasanth.gateway.ratelimit..")
            .layer("Auth").definedBy("io.vasanth.gateway.auth..")

            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Pipeline").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Cache").mayOnlyBeAccessedByLayers("Pipeline")
            .whereLayer("Provider").mayOnlyBeAccessedByLayers("Pipeline")
            .as("the request path flows one way: web -> pipeline -> cache/provider");

    /**
     * The domain must not depend on the framework.
     *
     * <p>Keeping {@code common} Spring-free is what lets both the data plane and the
     * control plane depend on it without inheriting each other's auto-configuration, and
     * what makes the cache-key and shard-ring logic testable with no context at all.
     */
    @ArchTest
    static final ArchRule commonStaysFrameworkFree = noClasses()
            .that().resideInAPackage("io.vasanth.gateway.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.servlet..", "io.micrometer..")
            .because("common is a plain library shared by every service; coupling it to "
                    + "Spring would force that framework on all of them");

    // =====================================================================
    // Security
    // =====================================================================

    /**
     * Credentials must never be compared with {@code equals}.
     *
     * <p>String comparison short-circuits on the first differing byte, and across enough
     * requests that timing difference lets an attacker recover a valid value one byte at a
     * time. {@code MessageDigest.isEqual} is constant-time.
     */
    @ArchTest
    static final ArchRule noStringEqualsOnSecrets = noClasses()
            .that().resideInAPackage("..auth..")
            .should().callMethod(String.class, "equals", Object.class)
            .because("credential comparison must be constant-time; use "
                    + "MessageDigest.isEqual to avoid a timing side channel");

    @ArchTest
    static final ArchRule noRandomForSecurity = noClasses()
            .that().resideInAnyPackage("..auth..", "..common.tenant..")
            .should().callConstructor(java.util.Random.class)
            .because("java.util.Random is predictable; anything security-bearing needs "
                    + "SecureRandom");

    /** Convenience holder so the import of JavaClasses is meaningful to readers. */
    static void requireImported(JavaClasses classes) {
        if (classes.isEmpty()) {
            throw new AssertionError("no classes were imported; the rules below prove nothing");
        }
    }
}
