package dev.hookflow.crypto;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing must be total and tamper-evident: whatever the secret, ids, timestamp
 * and body, the same tuple verifies and any single-field change does not.
 */
class SigningPropertyTest {

    @Property
    void roundTripsForAnyInput(
            @ForAll @StringLength(min = 1, max = 64) String secret,
            @ForAll @LongRange(min = 1, max = 1_000_000) long eventId,
            @ForAll @LongRange(min = 1, max = 1_000_000) long deliveryId,
            @ForAll @LongRange(min = 1_000_000_000, max = 2_000_000_000) long timestamp,
            @ForAll @StringLength(max = 200) String body) {

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        String signature = HmacSigner.sign(secretBytes, eventId, deliveryId, timestamp, bodyBytes);

        assertThat(HmacSigner.verify(secretBytes, eventId, deliveryId, timestamp, bodyBytes, signature)).isTrue();
    }

    @Property
    void changingTheBodyBreaksTheSignature(
            @ForAll @StringLength(min = 1, max = 32) String secret,
            @ForAll @StringLength(max = 50) String body,
            @ForAll @StringLength(min = 1, max = 8) String suffix) {

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] original = body.getBytes(StandardCharsets.UTF_8);
        byte[] tampered = (body + suffix).getBytes(StandardCharsets.UTF_8);

        String signature = HmacSigner.sign(secretBytes, 1, 2, 1_700_000_000L, original);

        assertThat(HmacSigner.verify(secretBytes, 1, 2, 1_700_000_000L, tampered, signature)).isFalse();
    }

    @Property
    void changingTheDeliveryIdBreaksTheSignature(
            @ForAll @StringLength(min = 1, max = 32) String secret,
            @ForAll @LongRange(min = 1, max = 100_000) long deliveryId,
            @ForAll @LongRange(min = 1, max = 100_000) long otherDeliveryId) {

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSigner.sign(secretBytes, 1, deliveryId, 1_700_000_000L, body);

        boolean verified = HmacSigner.verify(secretBytes, 1, otherDeliveryId, 1_700_000_000L, body, signature);

        // Deliberately allow the degenerate case where the generated ids collide.
        assertThat(verified).isEqualTo(deliveryId == otherDeliveryId);
    }

    @Property
    void signatureIsDeterministic(
            @ForAll @StringLength(min = 1, max = 32) String secret,
            @ForAll @StringLength(max = 100) String body) {

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        assertThat(HmacSigner.sign(secretBytes, 7, 9, 1_700_000_000L, bodyBytes))
                .isEqualTo(HmacSigner.sign(secretBytes, 7, 9, 1_700_000_000L, bodyBytes))
                .startsWith("v1=");
    }

    @Property
    void signatureIsHexOfAFixedLength(
            @ForAll @StringLength(min = 1, max = 32) String secret,
            @ForAll @StringLength(max = 64) String body) {

        String signature = HmacSigner.sign(secret.getBytes(StandardCharsets.UTF_8), 1, 1,
                1_700_000_000L, body.getBytes(StandardCharsets.UTF_8));

        // "v1=" + 32 byte HMAC rendered as hex
        assertThat(signature).hasSize(3 + 64);
        assertThat(signature.substring(3)).matches("[0-9a-f]{64}");
    }
}
