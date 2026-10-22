package dev.hookflow.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request signing.
 *
 * <p>Signed string: {@code v1:eventId:deliveryId:timestamp:rawBody}.
 * Binding the ids stops a valid signature from being replayed onto a different
 * delivery, and binding the timestamp lets the receiver reject stale requests.
 *
 * <p>Header: {@code X-HookFlow-Signature: v1=<hex>} plus
 * {@code X-HookFlow-Timestamp: <epoch seconds>}.
 */
public final class HmacSigner {

    public static final String VERSION = "v1";

    private HmacSigner() {
    }

    public static String sign(byte[] secret, long eventId, long deliveryId, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((VERSION + ":" + eventId + ":" + deliveryId + ":" + timestampSeconds + ":").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return VERSION + "=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign request", e);
        }
    }

    /** Constant-time verification, useful for the sample consumer and for tests. */
    public static boolean verify(byte[] secret, long eventId, long deliveryId, long timestampSeconds,
                                 byte[] body, String headerValue) {
        String expected = sign(secret, eventId, deliveryId, timestampSeconds, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                headerValue == null ? new byte[0] : headerValue.getBytes(StandardCharsets.UTF_8));
    }
}
