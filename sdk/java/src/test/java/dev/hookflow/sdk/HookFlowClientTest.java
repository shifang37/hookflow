package dev.hookflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookFlowClientTest {

    private MockWebServer server;
    private RecordingSleeper sleeper;
    private HookFlowClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sleeper = new RecordingSleeper();
        client = HookFlowClient.builder()
                .apiKey("hk_test")
                .baseUrl(server.url("/").toString())
                .sleeper(sleeper)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void sendsTheContractHeadersBodyAndReturnsTheResult() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setBody("{\"eventId\":7,\"deliveryCount\":2,\"idempotentReplay\":false}"));

        EventResult result = client.sendEvent("order.paid", Map.of("orderId", 12345));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/events");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer hk_test");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(request.getHeader("Idempotency-Key")).hasSize(36);

        JsonNode body = Json.MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.get("eventType").asText()).isEqualTo("order.paid");
        assertThat(body.get("payload").get("orderId").asInt()).isEqualTo(12345);

        assertThat(result.getEventId()).isEqualTo(7);
        assertThat(result.getDeliveryCount()).isEqualTo(2);
        assertThat(result.isIdempotentReplay()).isFalse();
    }

    @Test
    void parsesAnIdempotentReplay() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"eventId\":7,\"deliveryCount\":2,\"idempotentReplay\":true}"));

        EventResult result = client.sendEvent("order.paid", Map.of("orderId", 12345));

        assertThat(result.getEventId()).isEqualTo(7);
        assertThat(result.isIdempotentReplay()).isTrue();
    }

    @Test
    void usesTheExplicitIdempotencyKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":1,\"deliveryCount\":0,\"idempotentReplay\":false}"));

        client.sendEvent("order.paid", Map.of("orderId", 1), "order-1-paid");

        assertThat(server.takeRequest().getHeader("Idempotency-Key")).isEqualTo("order-1-paid");
    }

    @Test
    void generatesADifferentKeyForEachNewRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":1,\"deliveryCount\":0,\"idempotentReplay\":false}"));
        server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":2,\"deliveryCount\":0,\"idempotentReplay\":false}"));

        client.sendEvent("order.paid", Map.of("orderId", 1));
        client.sendEvent("order.paid", Map.of("orderId", 2));

        String first = server.takeRequest().getHeader("Idempotency-Key");
        String second = server.takeRequest().getHeader("Idempotency-Key");
        assertThat(first).hasSize(36);
        assertThat(second).hasSize(36);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void reusesOneSerializedBodyForEveryRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"BOOM\"}"));
        server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":1,\"deliveryCount\":0,\"idempotentReplay\":false}"));

        client.sendEvent("order.paid", Map.of("orderId", 1, "note", "héllo"));

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        String firstBody = first.getBody().readUtf8();
        String secondBody = second.getBody().readUtf8();
        assertThat(second.getHeader("Idempotency-Key")).isEqualTo(first.getHeader("Idempotency-Key"));
        assertThat(secondBody).isEqualTo(firstBody).contains("héllo");
    }

    @Test
    void rejectsAnEmptyApiKeyBeforeSending() {
        assertThatThrownBy(() -> HookFlowClient.builder().apiKey(" ").baseUrl("http://localhost").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void rejectsANonHttpBaseUrlBeforeSending() {
        assertThatThrownBy(() -> HookFlowClient.builder().apiKey("hk_test").baseUrl("ftp://example.com").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");

        assertThatThrownBy(() -> HookFlowClient.builder().apiKey("hk_test").baseUrl("not a url").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> HookFlowClient.builder()
                .apiKey("hk_test")
                .baseUrl("http://localhost")
                .readTimeout(java.time.Duration.ZERO)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout");
    }

    @Test
    void rejectsAnEventTypeOutsideTheServerContractWithoutSending() {
        assertThatThrownBy(() -> client.sendEvent("order paid!", Map.of("orderId", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectsANullPayload() {
        assertThatThrownBy(() -> client.sendEvent("order.paid", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectsAnIdempotencyKeyLongerThanTheContract() {
        assertThatThrownBy(() -> new EventRequest("order.paid", Map.of(), "k".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        assertThatThrownBy(() -> new EventRequest("order.paid", Map.of(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsAPayloadLargerThanTheServerLimitWithoutSending() {
        String tooLarge = "x".repeat(HookFlowClient.MAX_PAYLOAD_BYTES);

        assertThatThrownBy(() -> client.sendEvent("order.paid", tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("65536");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectsAPayloadThatJacksonCannotSerialize() {
        Object cyclic = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };

        assertThatThrownBy(() -> client.sendEvent("order.paid", cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void closingTwiceIsSafe() {
        client.close();
        client.close();
    }
}
