package dev.hookflow.messaging;

/**
 * The work message handed from the relay to a worker.
 *
 * <p>{@code attemptNo} is both the routing hint and the idempotency fence: a
 * worker only claims the delivery when the stored {@code attempt_count} is
 * exactly {@code attemptNo - 1}, so a redelivered message for an attempt that
 * already ran is harmlessly ignored.
 */
public record DeliveryMessage(
        long deliveryId,
        int attemptNo,
        long tenantId,
        long eventId,
        long endpointId) {
}
