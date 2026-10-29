package dev.hookflow.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Receiver-side verification of the webhooks HookFlow delivers.
 *
 * <h2>The signed string</h2>
 * <pre>
 * v1:eventId:deliveryId:timestamp:rawBody
 * </pre>
 * <p>where {@code timestamp} is the value of {@code X-HookFlow-Timestamp} (epoch
 * <strong>seconds</strong>, not milliseconds) and {@code rawBody} is the exact bytes
 * received. The signature is {@code v1=} followed by the lowercase hex HMAC-SHA256
 * of those bytes with the endpoint secret. This mirrors
 * {@code dev.hookflow.crypto.HmacSigner} on the server; both sides assert the same
 * reference vector so the format cannot drift.
 *
 * <h2>Raw bytes matter</h2>
 * <p>Verify the request body <em>before</em> deserialising it, and pass the bytes the
 * HTTP layer received. Re-serialising a parsed object changes field order and
 * whitespace, which changes the signature. Most frameworks expose the raw body
 * (Spring's {@code @RequestBody byte[]}, a servlet's {@code getInputStream()}); use
 * that.
 *
 * <h2>Replay protection</h2>
 * <p>Verification does two independent checks: the timestamp must be within the
 * tolerance window and the HMAC must match. Skipping either one is a vulnerability 鈥? * a signature without a window can be replayed forever, and a window without a
 * signature accepts forged requests.
 */
public final class HookFlowWebhook {

    /** Header carrying {@code v1=<hex>}. */
    public static final String SIGNATURE_HEADER = "X-HookFlow-Signature";

    /** Header carrying the epoch-second timestamp used in the signature. */
    public static final String TIMESTAMP_HEADER = "X-HookFlow-Timestamp";

    /** Header carrying the event id used in the signature. */
    public static final String EVENT_ID_HEADER = "X-HookFlow-Event-Id";

    /** Header carrying the delivery id used in the signature. */
    public static final String DELIVERY_ID_HEADER = "X-HookFlow-Delivery-Id";

    /** Default replay window: 卤5 minutes, matching the server documentation. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    /**
     * Values at or above this are assumed to be milliseconds rather than seconds.
     * The current epoch in seconds is around 1.8e9; 1e11 seconds is in the year 5138.
     */
    private static final long MAX_PLAUSIBLE_EPOCH_SECONDS = 100_000_000_000L;

    private static final String VERSION = "v1";

    private HookFlowWebhook() {
    }

    /**
     * Verifies a webhook with the default 卤300 s window and the system clock.
     *
     * @param secret  endpoint signing secret, as UTF-8
     * @param headers a case-insensitive lookup for HTTP headers; a plain map is fine
     * @param rawBody the exact request body bytes that were received
     * @return the verification result
     */
    public static Verification verify(String secret, Map<String, String> headers, byte[] rawBody) {
        return verify(toBytes(secret), caseInsensitive(headers), rawBody, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /**
     * Verifies a webhook with the default 卤300 s window and the system clock.
     *
     * @param secret  endpoint signing secret bytes
     * @param headers a case-insensitive lookup for HTTP headers; a plain map is fine
     * @param rawBody the exact request body bytes that were received
     * @return the verification result
     */
    public static Verification verify(byte[] secret, Map<String, String> headers, byte[] rawBody) {
        return verify(secret, caseInsensitive(headers), rawBody, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /**
     * Verifies a webhook with the default 卤300 s window and the system clock.
     *
     * <p>Use this overload with a framework's header accessor, for example
     * {@code headers::get}. The accessor should be case-insensitive, as HTTP header
     * APIs are; the SDK also retries the canonical name in lower and upper case.
     *
     * @param secret  endpoint signing secret, as UTF-8
     * @param headers accessor from header name to value, or {@code null}
     * @param rawBody the exact request body bytes that were received
     * @return the verification result
     */
    public static Verification verify(String secret, Function<String, String> headers, byte[] rawBody) {
        return verify(toBytes(secret), headers, rawBody, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /**
     * Verifies a webhook with the default 卤300 s window and the system clock.
     *
     * @param secret  endpoint signing secret bytes
     * @param headers accessor from header name to value, or {@code null}
     * @param rawBody the exact request body bytes that were received
     * @return the verification result
     */
    public static Verification verify(byte[] secret, Function<String, String> headers, byte[] rawBody) {
        return verify(secret, headers, rawBody, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /**
     * Verifies a webhook with a custom window and clock, taking the secret as a string.
     *
     * @param secret    endpoint signing secret, as UTF-8
     * @param headers   a case-insensitive lookup for HTTP headers; a plain map is fine
     * @param rawBody   the exact request body bytes that were received
     * @param tolerance how far the timestamp may be from the clock, in either direction
     * @param clock     clock used to evaluate the timestamp window
     * @return the verification result
     */
    public static Verification verify(String secret, Map<String, String> headers, byte[] rawBody,
                                      Duration tolerance, Clock clock) {
        return verify(toBytes(secret), caseInsensitive(headers), rawBody, tolerance, clock);
    }

    /**
     * Verifies a webhook with a custom window and clock, taking the secret as a string.
     *
     * @param secret    endpoint signing secret, as UTF-8
     * @param headers   accessor from header name to value
     * @param rawBody   the exact request body bytes that were received
     * @param tolerance how far the timestamp may be from the clock, in either direction
     * @param clock     clock used to evaluate the timestamp window
     * @return the verification result
     */
    public static Verification verify(String secret, Function<String, String> headers, byte[] rawBody,
                                      Duration tolerance, Clock clock) {
        return verify(toBytes(secret), headers, rawBody, tolerance, clock);
    }

    /**
     * Verifies a webhook with a custom window and clock.
     *
     * <p>The clock is injectable so tests can place "now" next to the reference
     * vector without sleeping or depending on the machine's clock.
     *
     * @param secret    endpoint signing secret bytes; must not be {@code null}
     * @param headers   accessor from header name to value; must not be {@code null}
     * @param rawBody   the exact request body bytes that were received; must not be {@code null}
     * @param tolerance how far the timestamp may be from the clock, in either direction;
     *                  must not be negative
     * @param clock     clock used to evaluate the timestamp window; must not be {@code null}
     * @return the verification result
     */
    public static Verification verify(byte[] secret, Function<String, String> headers, byte[] rawBody,
                                      Duration tolerance, Clock clock) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        Objects.requireNonNull(tolerance, "tolerance must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }

        String signature = trimToNull(header(headers, SIGNATURE_HEADER));
        String timestampValue = trimToNull(header(headers, TIMESTAMP_HEADER));
        String eventIdValue = trimToNull(header(headers, EVENT_ID_HEADER));
        String deliveryIdValue = trimToNull(header(headers, DELIVERY_ID_HEADER));
        if (signature == null || timestampValue == null || eventIdValue == null || deliveryIdValue == null) {
            return Verification.invalid(Verification.Reason.MISSING_HEADER);
        }

        final long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        } catch (NumberFormatException e) {
            return Verification.invalid(Verification.Reason.MALFORMED_TIMESTAMP);
        }
        if (Math.abs(timestamp) >= MAX_PLAUSIBLE_EPOCH_SECONDS) {
            // Almost certainly milliseconds. Report it as malformed rather than stale:
            // "your timestamp unit is wrong" is the actionable message.
            return Verification.invalid(Verification.Reason.MALFORMED_TIMESTAMP);
        }
        long skewSeconds = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (Duration.ofSeconds(skewSeconds).compareTo(tolerance) > 0) {
            return Verification.invalid(Verification.Reason.STALE_TIMESTAMP);
        }

        final long eventId;
        final long deliveryId;
        try {
            eventId = Long.parseLong(eventIdValue);
            deliveryId = Long.parseLong(deliveryIdValue);
        } catch (NumberFormatException e) {
            // The ids are part of the signed string; an unusable id cannot have produced
            // a valid signature, so this is a signature failure, not a missing header.
            return Verification.invalid(Verification.Reason.BAD_SIGNATURE);
        }

        String expected = sign(secret, eventId, deliveryId, timestamp, rawBody);
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return matches ? Verification.success() : Verification.invalid(Verification.Reason.BAD_SIGNATURE);
    }

    /**
     * Computes the header value HookFlow would send for these inputs.
     *
     * <p>This is exposed for tests and for receivers that need to reproduce a
     * signature (for example, to compare against a stored sample). Production
     * receivers should call {@link #verify} instead.
     *
     * @param secret      endpoint signing secret bytes
     * @param eventId     event id
     * @param deliveryId  delivery id
     * @param timestamp   epoch seconds
     * @param rawBody     exact request body bytes
     * @return {@code v1=<lowercase hex>}
     */
    public static String sign(byte[] secret, long eventId, long deliveryId, long timestamp, byte[] rawBody) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((VERSION + ':' + Long.toString(eventId) + ':' + deliveryId + ':' + timestamp + ':')
                    .getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            return VERSION + '=' + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] toBytes(String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private static Function<String, String> caseInsensitive(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers must not be null");
        Map<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        normalized.putAll(headers);
        return normalized::get;
    }

    private static String header(Function<String, String> headers, String name) {
        String value = headers.apply(name);
        if (value == null) {
            value = headers.apply(name.toLowerCase(Locale.ROOT));
        }
        if (value == null) {
            value = headers.apply(name.toUpperCase(Locale.ROOT));
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
