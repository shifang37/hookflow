package dev.hookflow.it;

import com.sun.net.httpserver.HttpServer;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overload behaviour over the real HTTP surface.
 *
 * <p>The broker is paused, so nothing can be published and the outbox is the only
 * place work can go. With a tenant watermark of a handful of rows, the platform
 * has to stop accepting that tenant's events — and the important part is <em>how</em>
 * it stops: {@code 429} with a {@code Retry-After}, not a timeout, not an
 * out-of-memory stop, and not a 503 that would also punish every other tenant.
 *
 * <p>Then the broker comes back. The test asserts the two properties that make
 * shedding acceptable at all: the events that <em>were</em> accepted are all
 * delivered, and the tenant is let back in once its backlog drains. A shed that
 * never lifts is just an outage with better manners.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "hookflow.backpressure.tenant-high-watermark=5",
                "hookflow.backpressure.check-interval-ms=50",
                "hookflow.backpressure.outbox-high-watermark=1000000",
                "hookflow.backpressure.retry-after-seconds=2"
        })
class BackpressureIT extends AbstractIntegrationTest {

    private static HttpServer receiver;
    private static int receiverPort;

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    /** Never leave the shared broker paused for the classes that run after this one. */
    @org.junit.jupiter.api.AfterEach
    void ensureBrokerRuns() {
        try {
            unpauseBroker();
        } catch (RuntimeException ignored) {
            // Already running.
        }
    }

    static {
        try {
            receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.setExecutor(Executors.newFixedThreadPool(8));
            receiver.createContext("/hook", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            receiver.start();
            receiverPort = receiver.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the receiver", e);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;
    private String apiKey;

    @BeforeEach
    void setUp() {
        String name = "bp-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKey = apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read").token();
        endpointService.create(tenantId, "http://127.0.0.1:" + receiverPort + "/hook", "*", 8);
    }

    @Test
    @DisplayName("overload sheds with 429 + Retry-After, and the tenant is let back in after the drain")
    void overloadShedsThenRecovers() throws Exception {
        pauseBroker();

        int accepted = 0;
        HttpResponse<String> shed = null;
        for (int i = 0; i < 60 && shed == null; i++) {
            HttpResponse<String> response = submitEvent(i);
            if (response.statusCode() == 202) {
                accepted++;
            } else {
                shed = response;
            }
            // Give the gate's cached snapshot a chance to observe the growing backlog.
            Thread.sleep(60);
        }

        assertThat(accepted)
                .as("some events are accepted before the watermark is crossed")
                .isBetween(1, 20);
        assertThat(shed).as("the platform must refuse eventually, not keep buffering").isNotNull();
        assertThat(shed.statusCode())
                .as("a tenant over its own budget is throttled, not told the platform is down")
                .isEqualTo(ErrorCode.TENANT_BACKLOG_EXCEEDED.status().value());
        assertThat(shed.headers().firstValue("Retry-After"))
                .as("a shed response without Retry-After turns into a retry storm")
                .contains("2");
        Map<?, ?> body = objectMapper.readValue(shed.body(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.TENANT_BACKLOG_EXCEEDED.code());
        assertThat(body.get("error")).isEqualTo(ErrorCode.TENANT_BACKLOG_EXCEEDED.name());

        // ------------------------------------------------------- recovery
        unpauseBroker();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(180));
        while (Instant.now().isBefore(deadline)) {
            long total = deliveryMapper.count(tenantId, null, null);
            long succeeded = deliveryMapper.count(tenantId, null, "SUCCEEDED");
            if (total == accepted && succeeded == accepted) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(deliveryMapper.count(tenantId, null, "SUCCEEDED"))
                .as("every accepted event must still be delivered after the overload")
                .isEqualTo(accepted);

        // The backlog is empty, so the gate has to open again. It is cached, which is
        // why this is a poll rather than an immediate assertion.
        HttpResponse<String> afterDrain = null;
        for (int i = 0; i < 40; i++) {
            afterDrain = submitEvent(1_000 + i);
            if (afterDrain.statusCode() == 202) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(afterDrain).isNotNull();
        assertThat(afterDrain.statusCode())
                .as("shedding must lift once the backlog drains, otherwise it is an outage")
                .isEqualTo(202);
    }

    private HttpResponse<String> submitEvent(int index) throws Exception {
        String payload = "{\"eventType\":\"order.paid\",\"payload\":{\"n\":" + index + "}}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/events"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "bp-" + index + "-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void pauseBroker() {
        RABBITMQ.getDockerClient().pauseContainerCmd(RABBITMQ.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        RABBITMQ.getDockerClient().unpauseContainerCmd(RABBITMQ.getContainerId()).exec();
    }
}
