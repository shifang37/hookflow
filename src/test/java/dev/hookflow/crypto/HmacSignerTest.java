package dev.hookflow.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"event_id\":1,\"payload\":{\"amount\":42}}".getBytes(StandardCharsets.UTF_8);
    private static final long EVENT_ID = 7;
    private static final long DELIVERY_ID = 11;
    private static final long TIMESTAMP = 1_800_000_000L;

    @Test
    void verifiesWhatItSigned() {
        String signature = HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY);
        assertThat(signature).startsWith("v1=");
        assertThat(HmacSigner.verify(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY, signature)).isTrue();
    }

    @Test
    void rejectsTamperedBody() {
        String signature = HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY);
        byte[] tampered = "{\"event_id\":1,\"payload\":{\"amount\":43}}".getBytes(StandardCharsets.UTF_8);
        assertThat(HmacSigner.verify(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, tampered, signature)).isFalse();
    }

    @Test
    void rejectsReplayOntoAnotherDelivery() {
        String signature = HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY);
        assertThat(HmacSigner.verify(SECRET, EVENT_ID, DELIVERY_ID + 1, TIMESTAMP, BODY, signature)).isFalse();
    }

    @Test
    void rejectsWrongSecretAndStaleTimestamp() {
        String signature = HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY);
        byte[] otherSecret = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);
        assertThat(HmacSigner.verify(otherSecret, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY, signature)).isFalse();
        assertThat(HmacSigner.verify(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP + 1, BODY, signature)).isFalse();
    }

    @Test
    void rejectsMissingHeader() {
        assertThat(HmacSigner.verify(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY, null)).isFalse();
    }

    @Test
    void signatureIsDeterministicForTheSameInput() {
        assertThat(HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY))
                .isEqualTo(HmacSigner.sign(SECRET, EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY));
    }

    /**
     * Cross-repo anti-drift vector: the Java SDK asserts the same input/hex pair in
     * {@code sdk/java/src/test/java/dev/hookflow/sdk/HookFlowWebhookTest.java}.
     *
     * <p>If the signed string, the version prefix or the hex casing changes on either
     * side, one of the two suites fails. Do not update one without the other.
     */
    @Test
    void matchesTheJavaSdkReferenceVector() {
        byte[] secret = "s3cr3t".getBytes(StandardCharsets.UTF_8);
        byte[] body = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);

        assertThat(HmacSigner.sign(secret, 12, 34, 1_760_000_000L, body))
                .isEqualTo("v1=9c1e11fe90c12fb041fa3f1a13252152b8b666e3b736e7aab65c65d324a8d0d4");
    }
}
