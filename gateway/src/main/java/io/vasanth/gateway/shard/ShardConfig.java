package io.vasanth.gateway.shard;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.vasanth.gateway.common.shard.ConsistentHashRing;
import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;

/**
 * Builds the shard topology, or the single-shard equivalent when none is configured.
 *
 * <h2>The unsharded path is the default and is unchanged</h2>
 *
 * <p>With no {@code gateway.sharding.shards}, this produces a {@link ShardedDatabase} wrapping
 * the one {@link DatabaseClient} Spring Boot already auto-configured. Nothing about the
 * local setup, the integration tests or any existing deployment changes - they simply
 * route every tenant to a shard called {@code default}.
 *
 * <p>Keeping that path identical is deliberate. Sharding is the kind of feature that
 * tends to make the simple deployment harder in order to serve a deployment nobody has
 * yet, and the cost lands on whoever runs it first.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShardProperties.class)
public class ShardConfig {

    private static final Logger log = LoggerFactory.getLogger(ShardConfig.class);

    /**
     * Pool size PER SHARD, and smaller than the unsharded default on purpose.
     *
     * <p>Total connections are pods x shards x pool size. At 20 pods and 4 shards, a
     * pool of 16 would open 1,280 connections, which is well past a default PostgreSQL
     * {@code max_connections} of 100 per shard. Sharding multiplies the connection count
     * and it is easy to forget that the per-shard figure is the one that has to shrink.
     */
    private static final int PER_SHARD_POOL_SIZE = 8;
    private static final int PER_SHARD_INITIAL_SIZE = 2;

    /**
     * Replica pools are smaller still.
     *
     * <p>Replicas only serve Tier-2 reads, which are short - a single indexed ANN query.
     * The primary additionally carries writes and the eviction job.
     */
    private static final int REPLICA_POOL_SIZE = 4;

    @Bean
    ShardedDatabase shardedDatabase(
            ShardProperties properties,
            DatabaseClient autoConfiguredClient,
            ConnectionFactoryOptions baseOptions) {

        if (!properties.isSharded()) {
            log.info("no gateway.shards configured; running unsharded on the "
                    + "auto-configured datasource");
            return ShardedDatabase.single(autoConfiguredClient);
        }

        // A stream rather than a for-loop purely to keep PMD's
        // AvoidInstantiatingObjectsInLoops quiet. The rule is a false positive here -
        // one client set per configured shard, built once at startup, is exactly the
        // required number of objects - but a stream reads just as well and is cheaper
        // than carrying a documented exclusion for it.
        //
        // LinkedHashMap, so the startup log lists shards in configured order.
        Map<ShardId, ShardedDatabase.ShardClients> clients = properties.shards().stream()
                .collect(Collectors.toMap(
                        shard -> ShardId.of(shard.id()),
                        shard -> clientsFor(shard, baseOptions),
                        (first, second) -> {
                            // Unreachable: ShardProperties rejects duplicate ids at
                            // construction. Present because toMap's merge function is
                            // the only place a duplicate could pass silently, and a
                            // silent merge would halve the configured capacity.
                            throw new IllegalStateException("duplicate shard id");
                        },
                        LinkedHashMap::new));

        ShardRouter router = new ShardRouter(
                ConsistentHashRing.of(clients.keySet(), properties.virtualNodes()));

        log.info("sharded Tier-2 cache: {} shard(s), {} virtual nodes each, {} replica(s) total",
                clients.size(),
                properties.virtualNodes(),
                clients.values().stream()
                        .mapToInt(ShardedDatabase.ShardClients::replicaCount).sum());

        if (!properties.whaleBuckets().isEmpty()) {
            // Loud, because this is the one configuration that gives up the property the
            // whole sharding design rests on - for the named tenants only. A whale's
            // Tier-2 reads fan out across its buckets and merge, which is the
            // cross-shard nearest-neighbour search ADR-0006 otherwise refuses.
            log.warn("sub-sharded (whale) tenants configured: {}. Their Tier-2 reads "
                            + "FAN OUT across buckets instead of hitting one shard - see "
                            + "SubShard for why that is accepted here and nowhere else.",
                    properties.whaleBuckets());
        }

        return new ShardedDatabase(router, clients, properties.whaleBuckets());
    }

    /**
     * The connection options Spring Boot resolved, reused for every shard.
     *
     * <p>Credentials, SSL settings and driver options come from here, so a shard entry
     * needs only a URL. Per-shard credentials would mean passwords in a values file.
     */
    @Bean
    ConnectionFactoryOptions baseConnectionFactoryOptions(
            org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties r2dbc) {
        ConnectionFactoryOptions.Builder builder = ConnectionFactoryOptions.builder();
        if (r2dbc.getUsername() != null) {
            builder.option(ConnectionFactoryOptions.USER, r2dbc.getUsername());
        }
        if (r2dbc.getPassword() != null) {
            builder.option(ConnectionFactoryOptions.PASSWORD, r2dbc.getPassword());
        }
        return builder.build();
    }

    /** The primary and replica clients for one configured shard. */
    private static ShardedDatabase.ShardClients clientsFor(
            ShardProperties.Shard shard, ConnectionFactoryOptions baseOptions) {

        ShardId id = ShardId.of(shard.id());
        DatabaseClient primary = clientFor(
                shard.url(), baseOptions, PER_SHARD_POOL_SIZE, id + "-primary");
        List<DatabaseClient> replicas = shard.replicaUrls().stream()
                .map(url -> clientFor(url, baseOptions, REPLICA_POOL_SIZE, id + "-replica"))
                .toList();
        return new ShardedDatabase.ShardClients(primary, replicas);
    }

    private static DatabaseClient clientFor(
            String url, ConnectionFactoryOptions baseOptions, int maxSize, String poolName) {

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .from(ConnectionFactoryOptions.parse(url))
                // Base options are applied LAST, so they win.
                //
                // That ordering is the point: the shard URL supplies driver, host,
                // port, database and any driver options, while credentials come from
                // spring.r2dbc and OVERRIDE anything the URL carries. A shard entry
                // therefore cannot smuggle in a different user - which matters because
                // these URLs live in a values file while the credentials come from a
                // Secret.
                .from(baseOptions)
                .build();

        ConnectionFactory raw = ConnectionFactories.get(options);

        ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(raw)
                .name(poolName)
                .initialSize(PER_SHARD_INITIAL_SIZE)
                .maxSize(maxSize)
                .maxIdleTime(Duration.ofMinutes(30))
                // Validated on acquire: a shard whose primary failed over leaves stale
                // connections in the pool, and without this the first request after a
                // failover gets a dead connection rather than a fresh one.
                .validationQuery("SELECT 1")
                .build());

        return DatabaseClient.create(pool);
    }

}
