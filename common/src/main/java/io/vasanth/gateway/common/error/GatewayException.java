package io.vasanth.gateway.common.error;

import java.io.Serial;
import java.util.Objects;

/**
 * Carries a {@link GatewayError} through Reactor's error channel.
 *
 * <p>Reactor signals failure with a {@link Throwable}, so a typed error value still needs an
 * exception to travel in. This class is that envelope and nothing more - all meaning lives
 * in {@link #error()}, and handlers should switch on that rather than on exception types.
 *
 * <p><b>Stack traces are deliberately disabled.</b> The superclass constructor is called with
 * {@code writableStackTrace = false}, so construction does not walk the stack. That matters
 * because the most frequently constructed errors - 429 rate-limited, 503 overloaded - are
 * thrown precisely when the system is already saturated. Capturing a stack trace for each
 * would spend CPU describing an entirely expected condition at the exact moment there is
 * none to spare.
 *
 * <p>Debuggability is not lost: the originating code, tenant and request id are on the
 * structured log line and the trace span, which are more useful than a reactive stack trace
 * would have been anyway.
 */
public final class GatewayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient GatewayError error;

    public GatewayException(GatewayError error) {
        // message, cause, enableSuppression, writableStackTrace
        super(Objects.requireNonNull(error, "error must not be null").message(), null, false, false);
        this.error = error;
    }

    public GatewayError error() {
        return error;
    }

    public static GatewayException of(GatewayError error) {
        return new GatewayException(error);
    }
}
