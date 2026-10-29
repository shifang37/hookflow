package dev.hookflow.sdk;

import java.util.Objects;

/**
 * The outcome of {@link HookFlowWebhook#verify}.
 *
 * <p>The reason is part of the API on purpose: "missing header" and "bad signature"
 * are different incidents (a proxy stripping headers versus a forged request), and a
 * receiver that logs only "invalid" makes that difference unobservable.
 */
public final class Verification {

    /**
     * Why a webhook was rejected.
     */
    public enum Reason {

        /** One of the signature, timestamp, event-id or delivery-id headers was absent or blank. */
        MISSING_HEADER,

        /**
         * The timestamp header was not an epoch-second value. A millisecond timestamp
         * lands here: it is roughly a thousand times too large to be seconds.
         */
        MALFORMED_TIMESTAMP,

        /** The timestamp parsed, but it is outside the allowed window (default ±300 s). */
        STALE_TIMESTAMP,

        /** The signature did not match the expected {@code v1=<hex>} value. */
        BAD_SIGNATURE
    }

    private static final Verification VALID = new Verification(true, null);

    private final boolean valid;
    private final Reason reason;

    private Verification(boolean valid, Reason reason) {
        this.valid = valid;
        this.reason = reason;
    }

    /**
     * Returns the singleton valid result.
     *
     * <p>Named {@code success} rather than {@code valid} because Java does not allow
     * a static and an instance method with the same signature; the instance method
     * {@link #valid()} is the one callers use.
     *
     * @return a valid verification
     */
    public static Verification success() {
        return VALID;
    }

    /**
     * Creates a rejected result.
     *
     * @param reason why the webhook was rejected; must not be {@code null}
     * @return an invalid verification carrying the reason
     */
    public static Verification invalid(Reason reason) {
        return new Verification(false, Objects.requireNonNull(reason, "reason must not be null"));
    }

    /**
     * Tells whether the webhook may be processed.
     *
     * @return {@code true} only when the timestamp was inside the window and the
     *         signature matched
     */
    public boolean valid() {
        return valid;
    }

    /**
     * Returns the rejection reason.
     *
     * @return the reason when {@link #valid()} is {@code false}, otherwise {@code null}
     */
    public Reason reason() {
        return reason;
    }

    @Override
    public String toString() {
        return valid ? "Verification{valid}" : "Verification{invalid, reason=" + reason + '}';
    }
}
