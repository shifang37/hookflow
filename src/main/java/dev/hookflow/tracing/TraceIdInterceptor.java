package dev.hookflow.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Echoes the current trace id back to the caller.
 *
 * <p>The id is what makes a support conversation possible: "this delivery was slow"
 * becomes a Jaeger query instead of a log hunt. It is returned as a header rather
 * than in the body so that it is present on <em>every</em> response, including
 * errors produced before a controller ran.
 *
 * <p>Implemented as a {@link HandlerInterceptor} and not as a filter: a filter
 * ordered outside the tracing filter would run before a span exists, and the ones
 * ordered inside it are ordered by {@code Ordered} constants that this project does
 * not control. {@code preHandle} is unambiguous — the span is open and the response
 * is not committed.
 */
@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-HookFlow-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    @Value("${management.tracing.enabled:true}")
    private boolean tracingEnabled;

    public TraceIdInterceptor(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!tracingEnabled) {
            return true;
        }
        Tracer active = tracer.getIfAvailable();
        if (active == null) {
            return true;
        }
        Span span = active.currentSpan();
        if (span != null && span.context() != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        return true;
    }
}
