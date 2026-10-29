package dev.hookflow.sdk;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One event to publish.
 *
 * <p>An {@code EventRequest} owns its idempotency key from the moment it is created:
 * when the caller does not supply one, a random UUID is generated here and stored,
 * so every retry of the same request sends the same key. Generating the key at send
 * time instead would turn a retry into a brand-new event.
 *
 * <p>Instances are immutable and can be reused across threads. Reusing the same
 * instance across separate {@code sendEvent} calls is deliberate: the second call
 * replays the first (the server returns the original {@code eventId} and
 * {@code idempotentReplay=true}) instead of creating a duplicate.
 *
 * @see HookFlowClient#sendEvent(EventRequest)
 */
public final class EventRequest {

    /** The server's event-type contract: safe to embed in JSON without escaping. */
    static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final String eventType;
    private final Object payload;
    private final String idempotencyKey;

    /**
     * Creates a request with an automatically generated idempotency key.
     *
     * @param eventType event type, matching {@code [A-Za-z0-9._-]} (1-128 characters)
     * @param payload   any JSON-serialisable value; must not be {@code null}
     * @throws IllegalArgumentException if the event type is invalid or the payload is not serialisable
     */
    public EventRequest(String eventType, Object payload) {
        this(eventType, payload, null);
    }

    /**
     * Creates a request with an explicit idempotency key.
     *
     * @param eventType      event type, matching {@code [A-Za-z0-9._-]} (1-128 characters)
     * @param payload        any JSON-serialisable value; must not be {@code null}
     * @param idempotencyKey key of 1–255 characters, or {@code null} to generate one.
     *                       Use a key derived from your own business identity (for example
     *                       {@code order-12345-paid}) when you want an event to be
     *                       deduplicated across process restarts.
     * @throws IllegalArgumentException if the event type, key or payload is invalid
     */
    public EventRequest(String eventType, Object payload, String idempotencyKey) {
        this.eventType = validateEventType(eventType);
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.idempotencyKey = idempotencyKey == null
                ? UUID.randomUUID().toString()
                : validateIdempotencyKey(idempotencyKey);
    }

    /**
     * Returns the event type.
     *
     * @return the validated event type
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the payload as supplied by the caller.
     *
     * <p>This is the original object, not a serialised copy; the client serialises it
     * exactly once per send and reuses those bytes for every retry.
     *
     * @return the payload object
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * Returns the idempotency key that will be sent for this request.
     *
     * @return a 1–255 character key, generated when the caller did not supply one
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    static String validateEventType(String eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType must match [A-Za-z0-9._-]{1,128} but was \"" + eventType + "\"");
        }
        return eventType;
    }

    static String validateIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isEmpty() || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters but was "
                            + idempotencyKey.length());
        }
        return idempotencyKey;
    }

    @Override
    public String toString() {
        return "EventRequest{eventType='" + eventType + "', idempotencyKey='" + idempotencyKey + "'}";
    }
}
