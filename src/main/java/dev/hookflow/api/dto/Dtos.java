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

    public record ProvisionTenantRequest(String name, Integer rateLimit,
                                        Integer maxInFlight, Integer weight) {
    }

    public record ProvisionTenantResponse(long tenantId, String name, String apiKey, String keyPrefix) {
    }
}
