package dev.hookflow.sdk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookFlowErrorTest {

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
                .retryPolicy(RetryPolicy.builder().maxAttempts(1).build())
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void surfacesEveryFieldOfTheStructuredError() {
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"code\":40901,\"error\":\"IDEMPOTENCY_KEY_REUSED\","
                        + "\"message\":\"key reused with a different payload\","
                        + "\"requestId\":\"req-from-body\"}"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo(40901);
                    assertThat(e.getErrorName()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(e.getRequestId()).isEqualTo("req-from-body");
                    assertThat(e.getMessage()).contains("different payload").contains("req-from-body");
                    assertThat(e.toString()).contains("40901").contains("req-from-body");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void fallsBackToTheRequestIdHeaderWhenTheBodyHasNone() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("X-Request-Id", "req-from-header")
                .setBody("{\"code\":40100,\"error\":\"UNAUTHORIZED\"}"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(401);
                    assertThat(e.getRequestId()).isEqualTo("req-from-header");
                    assertThat(e.getCode()).isEqualTo(40100);
                });
    }

    @Test
    void keepsTheRawBodyWhenItIsNotTheDocumentedErrorShape() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("X-Request-Id", "req-boom")
                .setBody("upstream exploded"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(500);
                    assertThat(e.getCode()).isZero();
                    assertThat(e.getErrorName()).isNull();
                    assertThat(e.getRequestId()).isEqualTo("req-boom");
                    assertThat(e.getMessage()).contains("upstream exploded");
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void exposesRetryAfterOnTheFinalFailure() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("{\"code\":42902,\"error\":\"TENANT_BACKLOG_EXCEEDED\"}"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(7);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void rejectsABatchResponseWithTheWrongNumberOfItems() {
        server.enqueue(new MockResponse().setResponseCode(202).setBody(
                "[{\"index\":0,\"eventId\":1,\"deliveryCount\":1,\"replay\":false}]"));

        assertThatThrownBy(() -> client.sendEvents(List.of(
                new EventRequest("a", Map.of(), "k1"),
                new EventRequest("b", Map.of(), "k2"))))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getMessage()).contains("1 results for 2 events");
                    assertThat(e.getStatusCode()).isEqualTo(202);
                });
    }

    @Test
    void rejectsABatchResponseWithDuplicateIndexes() {
        server.enqueue(new MockResponse().setResponseCode(202).setBody(
                "[{\"index\":0,\"eventId\":1,\"deliveryCount\":1,\"replay\":false},"
                        + "{\"index\":0,\"eventId\":2,\"deliveryCount\":1,\"replay\":false}]"));

        assertThatThrownBy(() -> client.sendEvents(List.of(
                new EventRequest("a", Map.of(), "k1"),
                new EventRequest("b", Map.of(), "k2"))))
                .isInstanceOf(HookFlowException.class)
                .hasMessageContaining("index");
    }

    @Test
    void rejectsABatchResponseThatIsNotAnArray() {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"oops\":true}"));

        assertThatThrownBy(() -> client.sendEvents(List.of(new EventRequest("a", Map.of(), "k1"))))
                .isInstanceOf(HookFlowException.class)
                .hasMessageContaining("malformed batch response");
    }

    @Test
    void rejectsASuccessfulResponseWithAnUnparseableBody() {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("not json"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOf(HookFlowException.class)
                .hasMessageContaining("malformed event response");
    }

    @Test
    void rejectsASuccessfulResponseWithAnEmptyBody() {
        server.enqueue(new MockResponse().setResponseCode(202));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOf(HookFlowException.class);
    }

    @Test
    void rejectsAnEventTypeLongerThanTheServerContract() {
        assertThatThrownBy(() -> client.sendEvent("a".repeat(129), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void treatsUnknownJsonFieldsAsForwardCompatible() {
        server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":1,\"deliveryCount\":0,\"idempotentReplay\":false,"
                        + "\"newField\":\"ignored\"}"));

        EventResult result = client.sendEvent("order.paid", Map.of("orderId", 1));

        assertThat(result.getEventId()).isEqualTo(1);
        assertThat(result.toString()).contains("eventId=1");
    }

    @Test
    void modelsHaveReadableToStrings() {
        EventRequest request = new EventRequest("order.paid", Map.of("orderId", 1), "key-1");
        BatchItemResult item = new BatchItemResult(0, 5, 2, true);

        assertThat(request.toString()).contains("order.paid").contains("key-1");
        assertThat(item.toString()).contains("index=0").contains("replay=true");
        assertThat(new BatchResult(List.of(item)).toString()).contains("BatchResult");
    }
}
