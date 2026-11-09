package io.vasanth.gateway.common.llm;

import java.util.concurrent.atomic.AtomicLong;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Carries a streamed response's real token count back to the caller.
 *
 * <h2>Why the Reactor Context rather than a parameter</h2>
 *
 * <p>A streamed response is a {@code Flux<String>} of text deltas; the token count arrives
 * only in a terminal usage frame, after the last delta. Returning it would mean changing
 * the signature of every link in the chain - pipeline, registry, resilience decorator and
 * each provider - to thread a sink through purely so the last one can write to it.
 *
 * <p>The Context already propagates from subscriber to source across all of them, which is
 * exactly the direction needed, so the caller puts a cell in and whichever provider ends up
 * serving fills it. Nothing in between has to know.
 *
 * <p><b>Absence is meaningful and must stay distinguishable.</b> A provider that does not
 * report usage - Ollama, the mock, or a vendor ignoring {@code stream_options} - simply
 * never writes, and the cell stays at zero. The caller then falls back to estimating from
 * character count. Defaulting to anything other than zero here would make "not reported"
 * indistinguishable from "reported as nothing".
 */
public final class StreamUsage {

    /** Context key holding the cell a provider writes into. */
    public static final String CONTEXT_KEY = "gateway.stream.usage";

    private StreamUsage() {
    }

    /** A fresh cell, and the context entry that carries it. */
    public static Context withCell(AtomicLong cell) {
        return Context.of(CONTEXT_KEY, cell);
    }

    /**
     * Records the total tokens a streamed response actually used.
     *
     * <p>Silently does nothing when no cell is present, so a provider never has to care
     * whether its caller wanted the figure.
     */
    public static void record(ContextView context, long totalTokens) {
        if (totalTokens <= 0L || !context.hasKey(CONTEXT_KEY)) {
            return;
        }
        AtomicLong cell = context.get(CONTEXT_KEY);
        cell.set(totalTokens);
    }
}
