package dev.hookflow.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * W3C trace context carried across the three process boundaries this platform has:
 * the API request, the outbox row, and the broker message.
 *
 * <p>The carrier is the standard {@code traceparent} header string. Two properties
 * matter and both come from using the standard instead of a bespoke id:
 *
 * <ul>
 *   <li>A receiver can honour {@code traceparent} on the inbound webhook without
 *       knowing anything about HookFlow — the trace continues inside the customer's
 *       system, which is the point of a trace.</li>
 *   <li>It is stored inside the outbox {@code payload} JSON, not only in broker
 *       headers. Broker headers are lost when a row is re-armed after a crash or a
 *       re-publish, so headers alone would silently truncate the trace exactly in
 *       the cases worth debugging.</li>
 * </ul>
 *
 * <p>Every method degrades to a no-op when tracing is off, which is why the
 * {@link Tracer} and {@link Propagator} are optional: {@code mvn verify} must not
 * need a collector, and a missing tracer must never fail a delivery.
 */
@Component
public class TraceContextSupport {

    public static final String TRACEPARENT = "traceparent";

    /** W3C trace-context version 00: {@code 00-<32 hex>-<16 hex>-<2 hex>}. */
    private static final Pattern VALID = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceContextSupport(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** @return the {@code traceparent} of the currently active span, or null */
    public String currentTraceparent() {
        Tracer active = tracer.getIfAvailable();
        Propagator activePropagator = propagator.getIfAvailable();
        if (active == null || activePropagator == null) {
            return null;
        }
        Span span = active.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>(2);
        activePropagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    public Span startSpan(String name, String traceparent) {
        return startSpan(name, traceparent, null);
    }

    /**
     * Starts a span named {@code name} whose parent is the remote context in
     * {@code traceparent}. A malformed or absent value makes the span a child of
     * whatever is current instead — losing the link is acceptable, throwing on a
     * hot delivery path is not.
     *
     * @return the started span, or null when tracing is disabled
     */
    public Span startSpan(String name, String traceparent, Span.Kind kind) {
        Tracer active = tracer.getIfAvailable();
        if (active == null) {
            return null;
        }
        Span.Builder builder = active.spanBuilder().name(name);

        Propagator activePropagator = propagator.getIfAvailable();
        String parent = traceparent == null ? null : traceparent.trim();
        if (activePropagator != null && parent != null && VALID.matcher(parent).matches()) {
            Map<String, String> carrier = Map.of(TRACEPARENT, parent);
            Span.Builder extracted = activePropagator.extract(carrier, (headers, key) -> headers.get(key));
            if (extracted != null) {
                builder = extracted.name(name);
            }
        }
        if (kind != null) {
            builder = builder.kind(kind);
        }
        return builder.start();
    }

    /** Makes {@code span} current for the duration of the returned scope. */
    public Tracer.SpanInScope openScope(Span span) {
        Tracer active = tracer.getIfAvailable();
        if (active == null || span == null) {
            return null;
        }
        return active.withSpan(span);
    }

    /** Ends a span that was started by {@link #startSpan}, tolerating nulls. */
    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    /** Adds the current trace context to an outbound message carrier (AMQP headers). */
    public Map<String, String> currentHeaders() {
        String traceparent = currentTraceparent();
        return traceparent == null ? Map.of() : Map.of(TRACEPARENT, traceparent);
    }
}
