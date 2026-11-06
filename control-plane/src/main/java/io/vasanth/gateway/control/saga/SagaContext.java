package io.vasanth.gateway.control.saga;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable state shared between the steps of one saga run.
 *
 * <p>Steps need to pass values forward - the generated API key, the Redis keys that were
 * created - so that compensation knows what to undo. A plain record would not do: the
 * values do not exist until the steps that produce them have run.
 *
 * <p>Concurrency: steps execute sequentially, so contention is not the concern. The
 * concurrent map is here because the orchestrator may read the context from a different
 * thread than the one that wrote it, and a plain HashMap offers no happens-before
 * guarantee across that handoff.
 */
public final class SagaContext {

    private final UUID sagaId;
    private final String aggregateId;
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public SagaContext(UUID sagaId, String aggregateId) {
        this.sagaId = sagaId;
        this.aggregateId = aggregateId;
    }

    public UUID sagaId() {
        return sagaId;
    }

    /** The business entity this saga acts on - a tenant id, for provisioning. */
    public String aggregateId() {
        return aggregateId;
    }

    public void put(String key, Object value) {
        values.put(key, value);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        return value == null ? null : type.cast(value);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    @Override
    public String toString() {
        // Keys only. Values can include a freshly generated API key, and a saga context
        // routinely ends up in a log line or an exception message.
        return "SagaContext[id=" + sagaId + ", aggregate=" + aggregateId
                + ", keys=" + values.keySet() + ']';
    }
}
