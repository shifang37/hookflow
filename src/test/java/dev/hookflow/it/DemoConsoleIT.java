package dev.hookflow.it;

import dev.hookflow.demo.DemoRegistry;
import dev.hookflow.demo.DemoReceiver;
import dev.hookflow.demo.DemoTenant;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.TenantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo console is part of the product, so it gets the same treatment as the rest:
 * it has to be proven against the real pipeline, not just looked at.
 *
 * <p>These tests drive the console exactly like a browser would — over HTTP, through
 * the same {@code /v1/demo/**} endpoints the page calls — and then assert on the
 * database and on what the fake customer actually received. If the console renders a
 * number that the tables do not support, these fail.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "hookflow.demo.enabled=true",
                "hookflow.demo.simulator-rate-per-second=4"
        })
class DemoConsoleIT extends AbstractIntegrationTest {

    /** A port for the in-process fake customer, chosen once so the property can bind. */
    private static final int RECEIVER_PORT = freePort();

    @DynamicPropertySource
    static void demoProperties(DynamicPropertyRegistry registry) {
        registry.add("hookflow.demo.receiver-port", () -> RECEIVER_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the demo receiver", e);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DemoRegistry registry;
    @Autowired
    private DemoReceiver receiver;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointMapper endpointMapper;

    @BeforeEach
    void reset() throws Exception {
        post("/v1/demo/reset");
    }

    @AfterEach
    void restore() throws Exception {
        post("/v1/demo/receiver?mode=OK&delayMillis=0");
        DemoTenant acme = registry.bySlug("acme").orElseThrow();
        post("/v1/demo/tenants/" + acme.tenantId() + "/budget?maxInFlight=32");
        post("/v1/demo/simulator?running=true&rate=4");
    }

    @Test
    @DisplayName("two tenants are provisioned, each with a real endpoint pointing at the fake receiver")
    void provisionsTwoTenantsWithEndpoints() {
        List<DemoTenant> tenants = registry.tenants();

        assertThat(tenants).hasSize(2);
        assertThat(tenants).extracting(DemoTenant::slug).containsExactly("acme", "quick");
        assertThat(tenants).extracting(DemoTenant::subscribedTypes).contains("*", "order.paid");

        for (DemoTenant tenant : tenants) {
            Endpoint endpoint = endpointMapper.findById(tenant.endpointId()).orElseThrow();
            assertThat(endpoint.url())
                    .as("the fake customer must be the one the console shows")
                    .isEqualTo("http://127.0.0.1:" + RECEIVER_PORT + "/hook/" + tenant.slug());
            assertThat(tenant.apiKey()).as("a real API key, usable over HTTP").startsWith("hk_");
        }
    }

    @Test
    @DisplayName("the simulated business system drives real deliveries, and the receiver verifies the signature itself")
    void simulatorProducesVerifiedDeliveries() throws Exception {
        post("/v1/demo/simulator?running=true&rate=20");

        DemoTenant acme = registry.bySlug("acme").orElseThrow();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        long succeeded = 0;
        while (Instant.now().isBefore(deadline)) {
            succeeded = deliveryMapper.count(acme.tenantId(), null, DeliveryStatus.SUCCEEDED.name());
            if (succeeded >= 2) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(succeeded)
                .as("the simulated order service must get events through the public API")
                .isGreaterThanOrEqualTo(2);
        assertThat(receiver.signatureOkCount())
                .as("every webhook is signed, and the receiver checked it with its own secret")
                .isGreaterThanOrEqualTo(2);
        assertThat(receiver.signatureBadCount()).isZero();
    }

    @Test
    @DisplayName("a broken receiver produces retries and a dead letter, and replay recovers it")
    void brokenReceiverBecomesDeadAndReplayRecovers() throws Exception {
        DemoTenant acme = registry.bySlug("acme").orElseThrow();
        post("/v1/demo/simulator?running=false");
        post("/v1/demo/receiver?mode=ERROR&delayMillis=0");

        // One event, through the console's button.
        post("/v1/demo/tenants/" + acme.tenantId() + "/event");

        Delivery dead = awaitDeliveryStatus(acme.tenantId(), DeliveryStatus.DEAD,
                Duration.ofSeconds(60));
        assertThat(dead.attemptCount())
                .as("a 5xx is retried until the attempt budget is gone")
                .isGreaterThan(1);
        assertThat(dead.lastErrorClass()).isEqualTo("SERVER_5XX");

        // The customer fixed their service; replay from the console.
        post("/v1/demo/receiver?mode=OK&delayMillis=0");
        post("/v1/demo/deliveries/" + dead.id() + "/replay");

        Delivery replayed = awaitDeliveryStatus(acme.tenantId(), DeliveryStatus.SUCCEEDED,
                Duration.ofSeconds(60), dead.id());
        assertThat(replayed.id()).isEqualTo(dead.id());
        assertThat(replayed.attemptCount()).isEqualTo(dead.attemptCount() + 1);
    }

    @Test
    @DisplayName("the console's budget button really changes the scheduler's budget")
    void budgetButtonChangesTheBudget() throws Exception {
        DemoTenant acme = registry.bySlug("acme").orElseThrow();
        // Do not assume the starting value: another test in this class may have moved it.
        post("/v1/demo/tenants/" + acme.tenantId() + "/budget?maxInFlight=32");

        post("/v1/demo/tenants/" + acme.tenantId() + "/budget?maxInFlight=4");

        assertThat(tenantMapper.findById(acme.tenantId()).orElseThrow().maxInFlight()).isEqualTo(4);
        assertThat(registry.bySlug("acme").orElseThrow().maxInFlight()).isEqualTo(4);
        Map<String, Object> state = state();
        List<?> tenants = (List<?>) state.get("tenants");
        Map<?, ?> acmeView = tenants.stream()
                .map(Map.class::cast)
                .filter(view -> "acme".equals(view.get("slug")))
                .findFirst()
                .orElseThrow();
        assertThat(acmeView.get("maxInFlight")).isEqualTo(4);
    }

    @Test
    @DisplayName("the state payload carries everything the page draws, from the real tables")
    void statePayloadIsComplete() throws Exception {
        Map<String, Object> state = state();

        assertThat(state.keySet()).contains("now", "platform", "simulator", "receiver", "tenants",
                "deliveries", "jaegerUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) state.get("platform");
        assertThat(platform.keySet()).contains("outboxBacklog", "succeeded", "shedTenant",
                "tenantHighWatermark", "fairnessEnabled");
        assertThat(platform.get("tenantHighWatermark"))
                .as("the demo profile lowers the watermark so 429s are reachable by clicking")
                .isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> receiverView = (Map<String, Object>) state.get("receiver");
        assertThat(receiverView.keySet()).contains("mode", "received", "signatureOk",
                "signatureBad", "recent");
        assertThat((List<?>) state.get("tenants")).hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/demo/state"))
                .timeout(Duration.ofSeconds(20))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(response.body(), Map.class);
    }

    private void post(String path) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode())
                .as("POST %s should succeed", path)
                .isEqualTo(200);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** Waits for any delivery of this tenant to reach the given status. */
    private Delivery awaitDeliveryStatus(long tenantId, DeliveryStatus expected, Duration timeout)
            throws InterruptedException {
        return awaitDeliveryStatus(tenantId, expected, timeout, -1);
    }

    private Delivery awaitDeliveryStatus(long tenantId, DeliveryStatus expected, Duration timeout,
                                         long deliveryId) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            List<Delivery> deliveries = deliveryMapper.list(tenantId, null, null, 20, 0);
            for (Delivery delivery : deliveries) {
                if (deliveryId > 0 && !delivery.id().equals(deliveryId)) {
                    continue;
                }
                latest = delivery;
                if (delivery.status() == expected) {
                    return delivery;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No delivery reached " + expected + " within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status())
                + ", attempts=" + (latest == null ? 0 : latest.attemptCount()));
    }
}
