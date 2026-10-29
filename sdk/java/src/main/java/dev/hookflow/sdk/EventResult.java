package dev.hookflow.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The server's answer to a single event submission.
 *
 * <p>The server returns {@code 202 Accepted} for a new event and {@code 200 OK} when
 * the idempotency key was already used. Both carry the same body shape; inspect
 * {@link #isIdempotentReplay()} when the distinction matters (for example, to decide
 * whether to increment your own "events published" metric).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EventResult {

    private final long eventId;
    private final int deliveryCount;
    private final boolean idempotentReplay;

    /**
     * Creates a result. Primarily for JSON deserialisation and tests.
     *
     * @param eventId          the event id assigned by HookFlow
     * @param deliveryCount    how many deliveries were fanned out for the event
     * @param idempotentReplay {@code true} when the idempotency key already existed
     */
    @JsonCreator
    public EventResult(@JsonProperty("eventId") long eventId,
                       @JsonProperty("deliveryCount") int deliveryCount,
                       @JsonProperty("idempotentReplay") boolean idempotentReplay) {
        this.eventId = eventId;
        this.deliveryCount = deliveryCount;
        this.idempotentReplay = idempotentReplay;
    }

    /**
     * Returns the event id.
     *
     * @return the event id assigned by HookFlow
     */
    public long getEventId() {
        return eventId;
    }

    /**
     * Returns how many deliveries the event was fanned out to.
     *
     * @return the number of deliveries created (or already existing) for this event
     */
    public int getDeliveryCount() {
        return deliveryCount;
    }

    /**
     * Tells whether this response was an idempotent replay.
     *
     * @return {@code true} when the idempotency key already existed and no new event
     *         was created; {@code false} for a freshly created event
     */
    public boolean isIdempotentReplay() {
        return idempotentReplay;
    }

    @Override
    public String toString() {
        return "EventResult{eventId=" + eventId
                + ", deliveryCount=" + deliveryCount
                + ", idempotentReplay=" + idempotentReplay + '}';
    }
}
