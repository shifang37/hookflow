package dev.hookflow.domain.delivery;

/**
 * Delivery lifecycle. The legal transitions are enforced by compare-and-set SQL
 * in {@code DeliveryMapper}; this enum only encodes the meaning of each state.
 *
 * <pre>
 *   PENDING ──claim──▶ DELIVERING ──2xx──▶ SUCCEEDED (terminal)
 *      ▲                   │
 *      │                   ├──retryable &amp;&amp; attempts left──▶ RETRY_WAIT ──claim──▶ DELIVERING
 *      │                   ├──retryable &amp;&amp; attempts exhausted──▶ DEAD (terminal)
 *      └──lease expired────┘
 *
 *   DEAD ──manual replay──▶ PENDING
 * </pre>
 */
public enum DeliveryStatus {

    PENDING,
    DELIVERING,
    RETRY_WAIT,
    SUCCEEDED,
    DEAD,
    CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELED;
    }

    /** States from which a worker may start a new attempt. */
    public boolean isClaimable() {
        return this == PENDING || this == RETRY_WAIT;
    }

    public boolean isRetryScheduled() {
        return this == RETRY_WAIT;
    }
}
