package dev.hookflow.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The per-event result of a batch submission.
 *
 * <p>The server may return items in any order; the client sorts them by
 * {@link #getIndex()} so the batch result always lines up with the request list.
 * Do not rely on array position in a raw response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchItemResult {

    private final int index;
    private final long eventId;
    private final int deliveryCount;
    private final boolean replay;

    /**
     * Creates a batch item result. Primarily for JSON deserialisation and tests.
     *
     * @param index         position in the request's {@code events[]} array
     * @param eventId       the event id (the original one when replayed)
     * @param deliveryCount how many deliveries the event was fanned out to
     * @param replay        {@code true} when the item did not create a new event
     */
    @JsonCreator
    public BatchItemResult(@JsonProperty("index") int index,
                           @JsonProperty("eventId") long eventId,
                           @JsonProperty("deliveryCount") int deliveryCount,
                           @JsonProperty("replay") boolean replay) {
        this.index = index;
        this.eventId = eventId;
        this.deliveryCount = deliveryCount;
        this.replay = replay;
    }

    /**
     * Returns the item's position in the request.
     *
     * @return the zero-based index into the {@code events[]} array that was sent
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the event id.
     *
     * @return the event id assigned by HookFlow, or the original id on replay
     */
    public long getEventId() {
        return eventId;
    }

    /**
     * Returns how many deliveries the event was fanned out to.
     *
     * @return the number of deliveries for this event
     */
    public int getDeliveryCount() {
        return deliveryCount;
    }

    /**
     * Tells whether this item was a replay.
     *
     * @return {@code true} when no new event was created: either the key already
     *         existed or it duplicated an earlier item in the same batch
     */
    public boolean isReplay() {
        return replay;
    }

    @Override
    public String toString() {
        return "BatchItemResult{index=" + index
                + ", eventId=" + eventId
                + ", deliveryCount=" + deliveryCount
                + ", replay=" + replay + '}';
    }
}
