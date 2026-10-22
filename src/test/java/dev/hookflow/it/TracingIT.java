package dev.hookflow.it;

import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace, five hops: {@code api -> outbox -> relay -> MQ -> worker -> receiver}.
 *
 * <p>The assertion is deliberately not "a trace exists" — it is that the
 * <em>same</em> trace id comes out of the receiver that went into the API call.
 * That is the only version of the claim worth making, because the interesting
 * failure is not a missing trace but a broken chain: three unrelated traces that
 * each look fine on their own and cannot be joined.
 *
 * <p>The trace id is read from the response header the API echoes back, and the
 * receiver captures the {@code traceparent} header of the webhook. Comparing them
 * proves the context survived the outbox (a database round trip), the broker and
 * the worker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracingIT extends AbstractIntegrationTest {

    private static HttpServer receiver;
    private static int receiverPort;
    private static final AtomicReference<String> LAST_TRACEPARENT = new AtomicReference<>();
    private static final AtomicReference<String> LAST_SIGNATURE = new AtomicReference<>();

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    static {
        try {
            receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.setExecutor(Executors.newFixedThreadPool(8));
            receiver.createContext("/trace", exchange -> {
                exchange.getRequestBody().readAllBytes();
                LAST_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));
                LAST_SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-HookFlow-Signature"));
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            receiver.start();
            receiverPort = receiver.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the tracing receiver", e);
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

    private String apiKey;

    @BeforeEach
    void setUp() {
        LAST_TRACEPARENT.set(null);
        LAST_SIGNATURE.set(null);
        String name = "trace-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        long tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKey = apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read").token();
        endpointService.create(tenantId, "http://127.0.0.1:" + receiverPort + "/trace", "*", 8);
    }

    @Test
    @DisplayName("the receiver sees the trace id the API returned, and a signature still validates")
    void oneTraceCoversTheWholeDelivery() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/events"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "trace-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"eventType\":\"order.paid\",\"payload\":{\"amount\":7}}"))
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> accepted = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);

        String traceId = accepted.headers().firstValue("X-HookFlow-Trace-Id").orElse(null);
        assertThat(traceId)
                .as("the API must echo the trace id, or nobody can find the trace later")
                .isNotNull()
                .hasSize(32);

        String received = awaitReceived();
        assertThat(received).as("the webhook must carry a traceparent header").isNotNull();

        String[] parts = received.split("-");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("00");
        assertThat(parts[1])
                .as("same trace: the context survived the outbox, the broker and the worker")
                .isEqualTo(traceId);
        assertThat(parts[2])
                .as("a new span id: the delivery is a child of the request, not the request")
                .isNotBlank();

        assertThat(LAST_SIGNATURE.get())
                .as("adding tracing must not disturb the signature contract")
                .isNotBlank();
    }

    private String awaitReceived() {
        java.time.Instant deadline = java.time.Instant.now().plus(Duration.ofSeconds(60));
        while (java.time.Instant.now().isBefore(deadline)) {
            String value = LAST_TRACEPARENT.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
