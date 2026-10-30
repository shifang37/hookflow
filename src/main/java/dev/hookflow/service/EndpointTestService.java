package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import dev.hookflow.worker.HttpDeliverer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The console's "test this endpoint" action.
 *
 * <p>This is deliberately <b>not</b> an event ingest: no event, delivery or attempt row is
 * written, and the call is synchronous so the operator sees the receiver's status code and
 * the first bytes of its response immediately. It reuses {@link HttpDeliverer} for the
 * request shape and signature, so "works in the test dialog but not in production" should
 * not be a class of bug this console can produce.
 *
 * <p>The synthetic request uses event/delivery id {@code 0} and attempt {@code 1}; receivers
 * should treat it as a probe, not as a real event. The endpoint's stored signing secret is
 * used, so a receiver that verifies signatures can verify this one too.
 */
@Service
public class EndpointTestService {

    /** One probe per endpoint per second, per application instance. */
    static final long MIN_INTERVAL_MS = 1_000L;

    private final EndpointService endpointService;
    private final EgressGuard egressGuard;
    private final HttpDeliverer httpDeliverer;
    private final ObjectMapper objectMapper;
    private final HookFlowProperties properties;
    private final ConcurrentHashMap<Long, Long> lastTestAt = new ConcurrentHashMap<>();

    public EndpointTestService(EndpointService endpointService,
                               EgressGuard egressGuard,
                               HttpDeliverer httpDeliverer,
                               ObjectMapper objectMapper,
                               HookFlowProperties properties) {
        this.endpointService = endpointService;
        this.egressGuard = egressGuard;
        this.httpDeliverer = httpDeliverer;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public record TestOutcome(
            boolean success,
            boolean retryable,
            Integer statusCode,
            String errorClass,
            int durationMs,
            String responsePreview) {
    }

    /**
     * @param payload optional JSON object; empty/null sends {@code {}}.
     * @throws ApiException 404 when the endpoint is not this tenant's, 429 when the probe
     *                      rate is exceeded, 413 when the payload is over the delivery limit
     */
    public TestOutcome test(long tenantId, long endpointId, Map<String, Object> payload) {
        Endpoint endpoint = endpointService.require(tenantId, endpointId);
        // Re-check at send time, not only at creation: a DNS name can change after the
        // endpoint was registered, and the probe is still an outbound request we initiate.
        egressGuard.validate(endpoint.url());
        enforceRateLimit(endpointId);

        String payloadJson = payload == null || payload.isEmpty()
                ? "{}"
                : objectMapper.writeValueAsString(payload);
        int maxPayloadBytes = properties.delivery().maxPayloadBytes();
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "test payload exceeds " + maxPayloadBytes + " bytes");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Event event = new Event(0L, tenantId, "endpoint.test", payloadJson, "endpoint-test", now);
        Delivery delivery = new Delivery(0L, tenantId, 0L, endpoint.id(), DeliveryStatus.PENDING,
                0, 1, null, null, null, null, null, now, now);

        HttpDeliverer.Outcome outcome = httpDeliverer.deliver(endpoint, event, delivery, 1);
        return new TestOutcome(outcome.success(), outcome.retryable(), outcome.statusCode(),
                outcome.errorClass(), outcome.durationMs(), outcome.responsePreview());
    }

    private void enforceRateLimit(long endpointId) {
        long now = System.currentTimeMillis();
        Long previous = lastTestAt.putIfAbsent(endpointId, now);
        if (previous != null && now - previous < MIN_INTERVAL_MS) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "endpoint test is limited to once per second; try again shortly");
        }
        if (previous != null) {
            lastTestAt.put(endpointId, now);
        }
    }
}
