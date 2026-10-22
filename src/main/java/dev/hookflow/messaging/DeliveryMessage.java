package dev.hookflow.messaging;

/**
 * The work message handed from the relay to a worker.
 *
 * <p>{@code attemptNo} is both the routing hint and the idempotency fence: a
 * worker only claims the delivery when the stored {@code attempt_count} is
 * exactly {@code attemptNo - 1}, so a redelivered message for an attempt that
 * already ran is harmlessly ignored.
 *
 * <p>{@code traceparent} is the W3C trace context of the API request that
 * accepted the event. It travels in the payload and not only in the AMQP headers
 * because a row can be re-published (crash window, broker rejection) long after
 * the headers were built: the JSON copy survives, the headers do not.
 */
public record DeliveryMessage(
        long deliveryId,
        int attemptNo,
        long tenantId,
        long eventId,
        long endpointId,
        String traceparent) {

    public DeliveryMessage withoutTrace() {
        return traceparent == null ? this
                : new DeliveryMessage(deliveryId, attemptNo, tenantId, eventId, endpointId, null);
    }
}
