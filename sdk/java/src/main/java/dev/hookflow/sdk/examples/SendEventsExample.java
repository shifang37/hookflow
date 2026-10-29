package dev.hookflow.sdk.examples;

import dev.hookflow.sdk.BatchResult;
import dev.hookflow.sdk.EventRequest;
import dev.hookflow.sdk.EventResult;
import dev.hookflow.sdk.HookFlowClient;
import dev.hookflow.sdk.HookFlowException;

import java.util.List;
import java.util.Map;

/**
 * A runnable publisher: one single event, one explicit idempotency key, then a batch.
 *
 * <p>Run it against a local server with:
 * <pre>{@code
 * set HOOKFLOW_API_KEY=hk_...
 * mvn -f sdk/java/pom.xml -q compile
 * java -cp "sdk/java/target/classes;<okhttp/jackson jars>" dev.hookflow.sdk.examples.SendEventsExample
 * }</pre>
 * The classpath part is awkward outside Maven; from an application, the two SDK
 * dependencies plus this jar are all you need. See the README for a Maven snippet.
 */
public final class SendEventsExample {

    private SendEventsExample() {
    }

    /**
     * Publishes the sample events.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        String apiKey = System.getenv().getOrDefault("HOOKFLOW_API_KEY", "hk_replace_me");
        String baseUrl = System.getenv().getOrDefault("HOOKFLOW_BASE_URL", "http://localhost:8080");

        try (HookFlowClient client = HookFlowClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build()) {

            // The key is generated from a UUID and reused by every retry.
            EventResult first = client.sendEvent("order.paid", Map.of("orderId", 12345, "amount", 99.00));
            System.out.printf("single: eventId=%d deliveries=%d replay=%s%n",
                    first.getEventId(), first.getDeliveryCount(), first.isIdempotentReplay());

            // An explicit key tied to your own business identity survives a process
            // restart: calling this twice returns the same eventId with replay=true.
            EventResult explicit = client.sendEvent(
                    "order.paid",
                    Map.of("orderId", 12346, "amount", 19.00),
                    "order-12346-paid");
            System.out.printf("explicit key: eventId=%d replay=%s%n",
                    explicit.getEventId(), explicit.isIdempotentReplay());

            BatchResult batch = client.sendEvents(List.of(
                    new EventRequest("order.paid", Map.of("orderId", 12347), "order-12347-paid"),
                    new EventRequest("order.shipped", Map.of("orderId", 12347))));
            System.out.printf("batch: %d results, %d replays%n",
                    batch.getResults().size(), batch.replayCount());
            batch.getResults().forEach(item -> System.out.printf(
                    "  index=%d eventId=%d deliveries=%d replay=%s%n",
                    item.getIndex(), item.getEventId(), item.getDeliveryCount(), item.isReplay()));
        } catch (HookFlowException e) {
            // requestId is the only thing support can use to find the server-side trace.
            System.err.printf("send failed: status=%d code=%d error=%s requestId=%s message=%s%n",
                    e.getStatusCode(), e.getCode(), e.getErrorName(), e.getRequestId(), e.getMessage());
            throw e;
        }
    }
}
