package dev.hookflow.domain.delivery;

import java.time.LocalDateTime;

/**
 * One (event, endpoint) pair.
 *
 * <p>{@code leaseToken} is a fencing token: every update that leaves
 * {@link DeliveryStatus#DELIVERING} must match it, so a worker that was declared
 * dead cannot overwrite the worker that replaced it.
 */
public record Delivery(
        Long id,
        Long tenantId,
        Long eventId,
        Long endpointId,
        DeliveryStatus status,
        int attemptCount,
        int maxAttempts,
        LocalDateTime nextAttemptAt,
        String leaseToken,
        LocalDateTime leaseUntil,
        String lastErrorClass,
        Integer lastStatusCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** The attempt number that a new claim would execute. */
    public int nextAttemptNo() {
        return attemptCount + 1;
    }
}
