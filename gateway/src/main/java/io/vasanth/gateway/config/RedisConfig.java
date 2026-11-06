package io.vasanth.gateway.config;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Redis wiring: the rate-limiter script and the clock. */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * Loads the atomic token-bucket script.
     *
     * <p>Spring Data Redis computes the script's SHA-1 once and issues {@code EVALSHA} on
     * every call, falling back to {@code EVAL} only if Redis reports the script is not
     * cached. That matters at this request rate: shipping the full script body on every
     * request would add a couple of kilobytes of egress to each rate-limit check.
     *
     * <p>The raw {@code List} type is forced on us - the script returns a heterogeneous
     * Redis multi-bulk reply and Spring Data offers no generic variant for that.
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * System clock, injected rather than called statically.
     *
     * <p>Every timestamp in this service comes from an injected {@link Clock} so that time
     * can be fixed in tests. Code that calls {@code Instant.now()} directly cannot be tested
     * for TTL or expiry behaviour without sleeping, and tests that sleep are slow and flaky.
     *
     * <p>Note this is <em>not</em> the clock the rate limiter uses: that one comes from
     * Redis, because gateway replicas on different nodes have independently drifting clocks
     * and a distributed bucket needs one shared time source. See {@code token_bucket.lua}.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
