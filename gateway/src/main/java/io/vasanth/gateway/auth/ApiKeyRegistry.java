package io.vasanth.gateway.auth;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Resolves a presented API key to the tenant that owns it.
 *
 * <h2>Why the hot path must not call the control plane</h2>
 *
 * <p>The obvious design has the gateway ask the control plane "who owns this key?" on every
 * request. That is wrong twice over: it adds a network round trip to a path budgeted at
 * 2 ms, and it makes the control plane a hard dependency of the data plane, so an admin
 * service restart would stop all traffic.
 *
 * <p>Instead the control plane <em>pushes</em> key material into Redis via Kafka, and the
 * gateway only ever reads its local view. The control plane can be down for an hour and
 * existing traffic is unaffected; only the propagation of new or revoked keys stalls.
 *
 * <h2>Revocation latency</h2>
 *
 * <p>The consequence, stated plainly: key revocation is eventually consistent. A revoked
 * key keeps working until the event is consumed - normally well under a second, but not
 * zero. For an immediate kill, the control plane deletes the Redis entry directly as well
 * as publishing the event, which makes revocation synchronous in practice while keeping
 * the asynchronous path as the durable one.
 */
public interface ApiKeyRegistry {

    /**
     * Resolves a raw API key.
     *
     * @param presentedKey the raw key from the request; never logged or persisted
     * @return the owning tenant, or empty if the key is unknown or revoked
     */
    Mono<TenantId> resolve(String presentedKey);
}
