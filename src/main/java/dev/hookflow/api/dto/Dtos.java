package dev.hookflow.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response payloads. These are deliberately separate from the domain records:
 * an {@code Endpoint} carries the encrypted signing secret and must never be
 * serialised by accident.
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateEndpointRequest(String url, String subscribedTypes, Integer maxAttempts,
                                        Integer maxConcurrency) {
    }

    public record EndpointResponse(
            long id,
            String url,
            String subscribedTypes,
            int maxAttempts,
            int maxConcurrency,
            String status,
            LocalDateTime createdAt) {
    }

    /** Returned exactly once, at creation time. */
    public record EndpointCreatedResponse(
            long id,
            String url,
            String subscribedTypes,
            int maxAttempts,
            int maxConcurrency,
            String status,
            String signingSecret) {
    }

    public record CreateEventRequest(String eventType, Object payload) {
    }

    public record EventResponse(long eventId, int deliveryCount, boolean idempotentReplay) {
    }

    /**
     * Batch submit body. The idempotency key is per item, not a request header: a
     * batch is a set of independent events, and each one needs its own replay
     * identity (documented in docs/api.md).
     */
    public record CreateEventBatchRequest(List<CreateEventBatchItem> events) {
    }

    public record CreateEventBatchItem(String eventType, Object payload, String idempotencyKey) {
    }

    /** One line of the batch response; {@code index} is the position in the request. */
    public record BatchEventResponse(int index, long eventId, int deliveryCount, boolean replay) {
    }

    public record DeliveryResponse(
            long id,
            long eventId,
            long endpointId,
            String status,
            int attemptCount,
            int maxAttempts,
            LocalDateTime nextAttemptAt,
            String lastErrorClass,
            Integer lastStatusCode,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record AttemptResponse(
            int attemptNo,
            String status,
            Integer statusCode,
            String errorClass,
            Integer durationMs,
            String responsePreview,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    public record DeliveryDetailResponse(DeliveryResponse delivery, List<AttemptResponse> attempts) {
    }

    /**
     * One synthetic delivery to an endpoint, executed synchronously for the console's
     * "test" button. No event, delivery or attempt row is written.
     *
     * <p>{@code statusCode} is null when the request never got an HTTP response
     * (connect error / timeout); {@code retryable} carries the same classification the
     * delivery worker would use.
     */
    public record EndpointTestResponse(
            long endpointId,
            boolean success,
            boolean retryable,
            Integer statusCode,
            String errorClass,
            int durationMs,
            String responsePreview) {
    }

    /**
     * One event with the payload it was accepted with and the deliveries it fanned out to.
     *
     * <p>{@code payload} is the <b>raw JSON string</b>, not a parsed object: the platform
     * never re-serialises customer data, so what is shown here is byte-for-byte what was
     * accepted and what every delivery attempt sent. A client has to parse it to pretty-print
     * it, and that is deliberate — parsing on this side would be the first step towards the
     * API returning something other than what was stored.
     *
     * <p>{@code deliveryCount} is the total; {@code deliveries} is capped (see
     * {@code EventController.DETAIL_DELIVERY_LIMIT}) so one event with hundreds of endpoints
     * cannot make this response unbounded. A client can tell the list is complete by
     * comparing the two.
     */
    public record EventDetailResponse(
            long eventId,
            String eventType,
            String payload,
            LocalDateTime createdAt,
            long deliveryCount,
            List<DeliveryResponse> deliveries) {
    }

    /**
     * Batch replay input: delivery ids in the order the caller wants results back.
     *
     * <p>Duplicates are rejected rather than silently de-duplicated: replaying the same
     * delivery twice in one request has no single correct answer (the second attempt would
     * see the first's PENDING state and be rejected), so it is a client bug worth a 400.
     */
    public record BatchReplayRequest(List<Long> deliveryIds) {
    }

    /**
     * One line of the batch replay response. {@code replayed = false} carries the reason
     * ({@code code} / {@code message}) and leaves {@code status} null; the request as a whole
     * still answered 200, because a partial result is the documented outcome.
     */
    public record ReplayResultResponse(
            long deliveryId,
            boolean replayed,
            String status,
            Integer code,
            String message) {
    }

    /**
     * Partial-success batch replay. {@code replayed + rejected == results.size()}, so a
     * client never has to count to know whether the batch fully succeeded.
     */
    public record BatchReplayResponse(
            int replayed,
            int rejected,
            List<ReplayResultResponse> results) {
    }

    public record ProvisionTenantRequest(String name, Integer rateLimit,
                                        Integer maxInFlight, Integer weight) {
    }

    public record ProvisionTenantResponse(long tenantId, String name, String apiKey, String keyPrefix) {
    }

    // ------------------------------------------------------------- read models

    /**
     * One line of the event stream.
     *
     * <p>The counters are a per-event roll-up of its deliveries so the console can
     * render one status cell per event without a follow-up request. They are
     * mutually exclusive: {@code deliveryCount = succeeded + failed + inFlight}.
     */
    public record EventSummaryResponse(
            long eventId,
            String eventType,
            int deliveryCount,
            int succeededCount,
            int failedCount,
            int inFlightCount,
            LocalDateTime createdAt) {
    }

    /** A tenant as the admin table shows it, with the counts it needs inline. */
    public record TenantResponse(
            long tenantId,
            String name,
            String status,
            int rateLimit,
            int maxInFlight,
            int weight,
            long endpointCount,
            long activeKeyCount,
            LocalDateTime createdAt) {
    }

    /** One row of "top failure reasons"; {@code errorClass} is null for unclassified failures. */
    public record FailureReasonResponse(
            String errorClass,
            Integer statusCode,
            long count,
            double percentage) {
    }

    /**
     * Tenant statistics for a fixed window.
     *
     * <p>{@code successRate} is a percentage (0-100) over <b>settled</b> deliveries:
     * {@code succeeded / (succeeded + dead)}. Deliveries still in flight are excluded
     * on purpose — counting them as successes would make a backlog look healthy.
     *
     * <p>{@code avgLatencyMs} is null when the window has no settled delivery yet.
     */
    public record TenantStatsResponse(
            long tenantId,
            String range,
            long totalEvents,
            long deliveries,
            long succeeded,
            long dead,
            long canceled,
            long inFlight,
            double successRate,
            Long avgLatencyMs,
            long backlog,
            List<FailureReasonResponse> topFailureReasons) {
    }

    /** Platform-wide version of {@link TenantStatsResponse}, for the console's overview page. */
    public record OverviewResponse(
            String range,
            long totalEvents,
            long deliveries,
            long succeeded,
            long dead,
            long canceled,
            long inFlight,
            double successRate,
            Long avgLatencyMs,
            long backlog,
            long activeTenants,
            long activeEndpoints) {
    }

    /**
     * Key metadata for the console. There is no secret here and there never can be:
     * the plaintext is only ever in {@link ProvisionTenantResponse#apiKey()}, and only
     * once, at creation.
     */
    public record ApiKeyResponse(
            long id,
            String keyPrefix,
            String scopes,
            String status,
            LocalDateTime createdAt,
            LocalDateTime revokedAt) {
    }

    /**
     * Partial update of a tenant's policy. Omitted fields keep their current value;
     * sending none of them is a 400 rather than a silent no-op.
     */
    /**
     * Partial tenant policy update. {@code status} is {@code ACTIVE} or {@code SUSPENDED};
     * omission keeps the current value, so an operator can change one knob at a time.
     */
    public record UpdateTenantRequest(Integer rateLimit, Integer maxInFlight, Integer weight,
                                      String status) {
    }

    /**
     * One x-axis point.
     *
     * <p>{@code events} and {@code attempts} are counted by when work <b>arrived</b>;
     * {@code succeeded}/{@code dead}/{@code canceled} by when it <b>settled</b>. A burst
     * therefore shows up as a spike in {@code events} followed by a wider, later hump in
     * the settlement counters, which is exactly the shape an operator needs to see.
     *
     * <p>Zero-filled: a quiet bucket is present with all counters at 0, never missing.
     */
    public record TimeseriesPointResponse(
            LocalDateTime bucketStart,
            long events,
            long attempts,
            long succeeded,
            long dead,
            long canceled) {
    }

    /**
     * A bucketed chart. {@code to} is exclusive and one bucket past the current one, so
     * the last point is the bucket still being filled.
     *
     * <p>{@code interval} is the resolved value — asking for {@code auto} answers with
     * the concrete interval that was chosen, so a client can label the axis without
     * re-deriving the rule.
     */
    public record TimeseriesResponse(
            String range,
            String interval,
            int bucketSeconds,
            LocalDateTime from,
            LocalDateTime to,
            List<TimeseriesPointResponse> points) {
    }

    /**
     * A duration histogram bucket. {@code upperBoundMs} is exclusive; a null bound means
     * the open-ended "slower than the last edge" bucket.
     */
    public record LatencyBucketResponse(
            Integer upperBoundMs,
            long count,
            double percentage) {
    }

    /**
     * Latency distribution of individual HTTP attempts.
     *
     * <p>Percentiles are nearest-rank and may be null when the window has no attempts.
     * {@code samples} is the denominator for every percentage below it, so a client can
     * tell "fast" apart from "unmeasured".
     */
    public record LatencyResponse(
            String range,
            long samples,
            Long p50Ms,
            Long p95Ms,
            Long p99Ms,
            List<LatencyBucketResponse> buckets) {
    }
}
