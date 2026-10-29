package dev.hookflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookFlowBatchTest {

    private MockWebServer server;
    private HookFlowClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = HookFlowClient.builder()
                .apiKey("hk_test")
                .baseUrl(server.url("/").toString())
                .sleeper(new RecordingSleeper())
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void sendsEveryItemWithItsOwnKeyAndNoRequestLevelKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody(
                "[{\"index\":0,\"eventId\":1,\"deliveryCount\":2,\"replay\":false},"
                        + "{\"index\":1,\"eventId\":2,\"deliveryCount\":0,\"replay\":false}]"));

        BatchResult result = client.sendEvents(List.of(
                new EventRequest("order.paid", Map.of("orderId", 1), "key-1"),
                new EventRequest("order.shipped", Map.of("orderId", 2), "key-2")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/events/batch");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer hk_test");
        assertThat(request.getHeader("Idempotency-Key")).isNull();

        JsonNode body = Json.MAPPER.readTree(request.getBody().readUtf8());
        JsonNode events = body.get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("order.paid");
        assertThat(events.get(0).get("idempotencyKey").asText()).isEqualTo("key-1");
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("order.shipped");
        assertThat(events.get(1).get("idempotencyKey").asText()).isEqualTo("key-2");

        assertThat(result.getResults()).hasSize(2);
        assertThat(result.replayCount()).isZero();
    }

    @Test
    void sortsResultsByIndexInsteadOfTrustingArrayOrder() {
        // The contract pairs results with the request by index, so a server that
        // returns them out of order must not shift event ids onto the wrong events.
        server.enqueue(new MockResponse().setResponseCode(202).setBody(
                "[{\"index\":1,\"eventId\":200,\"deliveryCount\":1,\"replay\":false},"
                        + "{\"index\":0,\"eventId\":100,\"deliveryCount\":1,\"replay\":false}]"));

        BatchResult result = client.sendEvents(List.of(
                new EventRequest("order.paid", Map.of("orderId", 1), "key-1"),
                new EventRequest("order.paid", Map.of("orderId", 2), "key-2")));

        assertThat(result.getResults()).extracting(BatchItemResult::getIndex).containsExactly(0, 1);
        assertThat(result.getResults()).extracting(BatchItemResult::getEventId).containsExactly(100L, 200L);
    }

    @Test
    void countsReplayedItems() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "[{\"index\":0,\"eventId\":1,\"deliveryCount\":1,\"replay\":true},"
                        + "{\"index\":1,\"eventId\":1,\"deliveryCount\":1,\"replay\":true},"
                        + "{\"index\":2,\"eventId\":3,\"deliveryCount\":1,\"replay\":false}]"));

        BatchResult result = client.sendEvents(List.of(
                new EventRequest("a", Map.of(), "same"),
                new EventRequest("a", Map.of(), "same"),
                new EventRequest("a", Map.of(), "other")));

        assertThat(result.replayCount()).isEqualTo(2);
    }

    @Test
    void acceptsTheFiveHundredItemLimit() throws Exception {
        List<EventRequest> requests = new ArrayList<>();
        StringBuilder response = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            requests.add(new EventRequest("order.paid", Map.of("orderId", i)));
            if (i > 0) {
                response.append(',');
            }
            response.append("{\"index\":").append(i)
                    .append(",\"eventId\":").append(i + 1)
                    .append(",\"deliveryCount\":1,\"replay\":false}");
        }
        response.append(']');
        server.enqueue(new MockResponse().setResponseCode(202).setBody(response.toString()));

        BatchResult result = client.sendEvents(requests);

        JsonNode body = Json.MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.get("events")).hasSize(500);
        assertThat(result.getResults()).hasSize(500);
    }

    @Test
    void rejectsFiveHundredAndOneItemsWithoutSending() {
        List<EventRequest> requests = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            requests.add(new EventRequest("order.paid", Map.of("orderId", i)));
        }

        assertThatThrownBy(() -> client.sendEvents(requests))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectsAnEmptyBatchWithoutSending() {
        assertThatThrownBy(() -> client.sendEvents(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void retriesTheExactSameBatchBodyAndKeys() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503)
                // Nonzero so OkHttp itself does not follow the 503 (see the retry tests).
                .setHeader("Retry-After", "1")
                .setBody("{\"code\":50301,\"error\":\"OVER_CAPACITY\"}"));
        server.enqueue(new MockResponse().setResponseCode(202).setBody(
                "[{\"index\":0,\"eventId\":1,\"deliveryCount\":0,\"replay\":false}]"));

        client.sendEvents(List.of(new EventRequest("order.paid", Map.of("orderId", 1), "stable-key")));

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        String firstBody = first.getBody().readUtf8();
        String secondBody = second.getBody().readUtf8();
        assertThat(secondBody).isEqualTo(firstBody).contains("stable-key");
    }
}
