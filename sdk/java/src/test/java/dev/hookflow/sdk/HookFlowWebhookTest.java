package dev.hookflow.sdk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookFlowWebhookTest {

    /**
     * Cross-repo anti-drift vector. The identical input/hex pair is asserted in the
     * server's {@code HmacSignerTest}; if either implementation changes the signed
     * string, one of the two suites fails.
     */
    private static final String SECRET = "s3cr3t";
    private static final byte[] BODY = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);
    private static final long EVENT_ID = 12;
    private static final long DELIVERY_ID = 34;
    private static final long TIMESTAMP = 1_760_000_000L;
    private static final String SIGNATURE =
            "v1=9c1e11fe90c12fb041fa3f1a13252152b8b666e3b736e7aab65c65d324a8d0d4";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC);

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put(HookFlowWebhook.SIGNATURE_HEADER, SIGNATURE);
        headers.put(HookFlowWebhook.TIMESTAMP_HEADER, Long.toString(TIMESTAMP));
        headers.put(HookFlowWebhook.EVENT_ID_HEADER, Long.toString(EVENT_ID));
        headers.put(HookFlowWebhook.DELIVERY_ID_HEADER, Long.toString(DELIVERY_ID));
        return headers;
    }

    private static Verification verify(Map<String, String> headers) {
        return HookFlowWebhook.verify(SECRET, headers, BODY, Duration.ofSeconds(300), CLOCK);
    }

    @Test
    void reproducesTheReferenceVector() {
        assertThat(HookFlowWebhook.sign(
                SECRET.getBytes(StandardCharsets.UTF_8), EVENT_ID, DELIVERY_ID, TIMESTAMP, BODY))
                .isEqualTo(SIGNATURE)
                .matches("v1=[0-9a-f]{64}");
    }

    @Test
    void acceptsTheReferenceVector() {
        Verification verification = verify(headers());

        assertThat(verification.valid()).isTrue();
        assertThat(verification.reason()).isNull();
        assertThat(verification.toString()).isEqualTo("Verification{valid}");
    }

    @Test
    void headerNamesAreCaseInsensitiveAndValuesAreTrimmed() {
        Map<String, String> lowerCase = new HashMap<>();
        lowerCase.put("x-hookflow-signature", "  " + SIGNATURE + "  ");
        lowerCase.put("x-hookflow-timestamp", " " + TIMESTAMP + " ");
        lowerCase.put("x-hookflow-event-id", " " + EVENT_ID + " ");
        lowerCase.put("x-hookflow-delivery-id", " " + DELIVERY_ID + " ");

        assertThat(verify(lowerCase).valid()).isTrue();
    }

    @Test
    void acceptsAFunctionAccessorThatOnlyKnowsLowerCaseNames() {
        // Frameworks differ: some preserve the wire casing, some normalise to lower
        // case. The function overload probes the canonical, lower and upper variants.
        Verification verification = HookFlowWebhook.verify(
                SECRET, headers()::get, BODY, Duration.ofSeconds(300), CLOCK);

        assertThat(verification.valid()).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        byte[] tampered = "{\"amount\":43}".getBytes(StandardCharsets.UTF_8);

        Verification verification = HookFlowWebhook.verify(
                SECRET.getBytes(StandardCharsets.UTF_8), headers()::get, tampered,
                Duration.ofSeconds(300), CLOCK);

        assertThat(verification.valid()).isFalse();
        assertThat(verification.reason()).isEqualTo(Verification.Reason.BAD_SIGNATURE);
        assertThat(verification.toString()).contains("BAD_SIGNATURE");
    }

    @Test
    void rejectsAWrongSecret() {
        Verification verification = HookFlowWebhook.verify(
                "wrong".getBytes(StandardCharsets.UTF_8), headers()::get, BODY,
                Duration.ofSeconds(300), CLOCK);

        assertThat(verification.reason()).isEqualTo(Verification.Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsAValidSignatureBoundToAnotherEvent() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.EVENT_ID_HEADER, "13");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsAValidSignatureBoundToAnotherDelivery() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.DELIVERY_ID_HEADER, "35");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsAStaleTimestamp() {
        Clock later = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP + 301), ZoneOffset.UTC);

        Verification verification = HookFlowWebhook.verify(
                SECRET, headers()::get, BODY, Duration.ofSeconds(300), later);

        assertThat(verification.reason()).isEqualTo(Verification.Reason.STALE_TIMESTAMP);
    }

    @Test
    void rejectsATimestampTooFarInTheFuture() {
        Clock earlier = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP - 301), ZoneOffset.UTC);

        assertThat(HookFlowWebhook.verify(SECRET, headers()::get, BODY, Duration.ofSeconds(300), earlier).reason())
                .isEqualTo(Verification.Reason.STALE_TIMESTAMP);
    }

    @Test
    void theWindowIsInclusive() {
        Clock edge = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP + 300), ZoneOffset.UTC);

        assertThat(HookFlowWebhook.verify(SECRET, headers()::get, BODY, Duration.ofSeconds(300), edge).valid())
                .isTrue();
    }

    @Test
    void honorsACustomTolerance() {
        Clock inside = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP + 10), ZoneOffset.UTC);
        Clock outside = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP + 11), ZoneOffset.UTC);

        assertThat(HookFlowWebhook.verify(SECRET, headers()::get, BODY, Duration.ofSeconds(10), inside).valid())
                .isTrue();
        assertThat(HookFlowWebhook.verify(SECRET, headers()::get, BODY, Duration.ofSeconds(10), outside).reason())
                .isEqualTo(Verification.Reason.STALE_TIMESTAMP);
    }

    @Test
    void reportsAnyMissingHeader() {
        for (String missing : new String[]{
                HookFlowWebhook.SIGNATURE_HEADER,
                HookFlowWebhook.TIMESTAMP_HEADER,
                HookFlowWebhook.EVENT_ID_HEADER,
                HookFlowWebhook.DELIVERY_ID_HEADER}) {
            Map<String, String> headers = new HashMap<>(headers());
            headers.remove(missing);

            assertThat(verify(headers).reason())
                    .as("missing %s", missing)
                    .isEqualTo(Verification.Reason.MISSING_HEADER);
        }
    }

    @Test
    void reportsABlankHeaderAsMissing() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.TIMESTAMP_HEADER, "   ");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.MISSING_HEADER);
    }

    @Test
    void reportsMillisecondsAsMalformedInsteadOfStale() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.TIMESTAMP_HEADER, "1760000000000");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.MALFORMED_TIMESTAMP);
    }

    @Test
    void reportsANonNumericTimestampAsMalformed() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.TIMESTAMP_HEADER, "not-a-timestamp");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.MALFORMED_TIMESTAMP);
    }

    @Test
    void reportsANonNumericEventIdAsABadSignature() {
        Map<String, String> headers = headers();
        headers.put(HookFlowWebhook.EVENT_ID_HEADER, "twelve");

        assertThat(verify(headers).reason()).isEqualTo(Verification.Reason.BAD_SIGNATURE);
    }

    @Test
    void verifiesThroughThePlainMapOverloadWithTheSystemClock() {
        long now = Instant.now().getEpochSecond();
        String signature = HookFlowWebhook.sign(
                SECRET.getBytes(StandardCharsets.UTF_8), EVENT_ID, DELIVERY_ID, now, BODY);
        Map<String, String> fresh = headers();
        fresh.put(HookFlowWebhook.TIMESTAMP_HEADER, Long.toString(now));
        fresh.put(HookFlowWebhook.SIGNATURE_HEADER, signature);

        assertThat(HookFlowWebhook.verify(SECRET, fresh, BODY).valid()).isTrue();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> HookFlowWebhook.verify(
                (String) null, headers(), BODY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("secret");
        assertThatThrownBy(() -> HookFlowWebhook.verify(SECRET, headers(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rawBody");
        assertThatThrownBy(() -> HookFlowWebhook.verify(
                SECRET, headers()::get, BODY, Duration.ofSeconds(-1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tolerance");
    }
}
