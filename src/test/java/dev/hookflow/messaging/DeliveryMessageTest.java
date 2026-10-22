package dev.hookflow.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of a work item is a compatibility surface: outbox rows written
 * by v0.2 are re-published by a v0.3 relay, so a missing field must deserialise
 * to {@code null} rather than fail. A row that cannot be parsed is moved to the
 * dead letter queue, i.e. the delivery is silently lost-ish — exactly the failure
 * mode this test exists to prevent.
 */
class DeliveryMessageTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("traceparent survives the JSON round trip")
    void traceparentRoundTrip() {
        DeliveryMessage message = new DeliveryMessage(11L, 2, 22L, 33L, 44L,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        DeliveryMessage parsed = objectMapper.readValue(
                objectMapper.writeValueAsString(message), DeliveryMessage.class);

        assertThat(parsed).isEqualTo(message);
        assertThat(parsed.traceparent()).isEqualTo(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("a v0.2 row (no traceparent) still parses")
    void oldRowsWithoutTraceparentParse() {
        String v02Payload = """
                {"deliveryId":11,"attemptNo":2,"tenantId":22,"eventId":33,"endpointId":44}
                """;

        DeliveryMessage parsed = objectMapper.readValue(v02Payload, DeliveryMessage.class);

        assertThat(parsed.deliveryId()).isEqualTo(11L);
        assertThat(parsed.attemptNo()).isEqualTo(2);
        assertThat(parsed.traceparent()).isNull();
    }

    @Test
    @DisplayName("a null traceparent is written as an explicit null, not dropped")
    void nullTraceparentIsSerialised() {
        String json = objectMapper.writeValueAsString(
                new DeliveryMessage(1L, 1, 2L, 3L, 4L, null));

        assertThat(json).contains("\"traceparent\":null");
    }

    @Test
    @DisplayName("withoutTrace keeps the identity and drops only the context")
    void withoutTraceIsIdentityPreserving() {
        DeliveryMessage message = new DeliveryMessage(1L, 3, 2L, 3L, 4L,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        DeliveryMessage stripped = message.withoutTrace();

        assertThat(stripped.traceparent()).isNull();
        assertThat(stripped.deliveryId()).isEqualTo(message.deliveryId());
        assertThat(stripped.attemptNo()).isEqualTo(message.attemptNo());
        assertThat(stripped.withoutTrace()).isSameAs(stripped);
    }
}
