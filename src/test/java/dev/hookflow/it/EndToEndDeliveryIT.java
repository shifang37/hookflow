package dev.hookflow.it;

import com.sun.net.httpserver.HttpServer;
import dev.hookflow.crypto.HmacSigner;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.DeliveryService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real chain: event -> MySQL outbox -> relay -> RabbitMQ -> worker -> HTTP.
 *
 * <p>Nothing is stubbed except the customer endpoint, which is a plain HTTP server
 * in this JVM. If this test passes, the delivery path works; if it fails, the
 * project's central claim is unproven.
 */
class EndToEndDeliveryIT extends AbstractIntegrationTest {

    private static HttpServer receiver;
    private static int receiverPort;
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger LAST_ATTEMPT = new AtomicInteger();
    private static volatile boolean lastSignatureValid;
    private static volatile byte[] signingSecret;
    private static volatile int nextStatus = 200;

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(Executors.newFixedThreadPool(4));
        receiver.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String signature = exchange.getRequestHeaders().getFirst("X-HookFlow-Signature");
            String timestamp = exchange.getRequestHeaders().getFirst("X-HookFlow-Timestamp");
            String eventId = exchange.getRequestHeaders().getFirst("X-HookFlow-Event-Id");
            String deliveryId = exchange.getRequestHeaders().getFirst("X-HookFlow-Delivery-Id");
            String attempt = exchange.getRequestHeaders().getFirst("X-HookFlow-Attempt");

            if (signingSecret != null && signature != null && timestamp != null) {
                lastSignatureValid = HmacSigner.verify(signingSecret,
                        Long.parseLong(eventId), Long.parseLong(deliveryId),
                        Long.parseLong(timestamp), body, signature);
            }
            LAST_ATTEMPT.set(attempt == null ? -1 : Integer.parseInt(attempt));
            HITS.incrementAndGet();

            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(nextStatus, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        receiver.start();
        receiverPort = receiver.getAddress().getPort();
    }

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private DeliveryAttemptMapper attemptMapper;
    @Autowired
    private DeliveryService deliveryService;

    private long tenantId;

    @BeforeEach
    void setUp() {
        HITS.set(0);
        LAST_ATTEMPT.set(-1);
        lastSignatureValid = false;
        nextStatus = 200;

        String name = "e2e-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
    }

    @Test
    @DisplayName("2xx: event is delivered once, signed, and recorded as SUCCEEDED")
    void successfulDelivery() {
        signingSecret = Base64.getUrlDecoder().decode(createEndpointReturningSecret("https://ignored.test"));
        long eventId = ingestOne();

        Delivery delivery = awaitStatus(eventId, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(30));

        assertThat(HITS.get()).as("exactly one HTTP call").isEqualTo(1);
        assertThat(lastSignatureValid).as("the receiver can verify the signature itself").isTrue();
        assertThat(LAST_ATTEMPT.get()).isEqualTo(1);
        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(delivery.lastStatusCode()).isEqualTo(200);

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(delivery.id());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryAttempt.SUCCEEDED);
        assertThat(attempts.getFirst().durationMs()).isNotNull();
    }

    @Test
    @DisplayName("5xx: the delivery is retried with a backoff, then eventually gives up as DEAD")
    void serverErrorsAreRetriedUntilExhausted() {
        nextStatus = 500;
        createEndpointReturningSecret("https://ignored.test");

        long eventId = ingestOne();

        // max-attempts defaults to 8; with a 200ms base delay the whole ladder is fast.
        Delivery delivery = awaitStatus(eventId, DeliveryStatus.DEAD, Duration.ofSeconds(60));

        assertThat(HITS.get()).isGreaterThan(1).as("a 5xx must be retried");
        assertThat(delivery.attemptCount()).isEqualTo(delivery.maxAttempts());
        assertThat(delivery.lastErrorClass()).isEqualTo("SERVER_5XX");
        assertThat(attemptMapper.listByDelivery(delivery.id()))
                .as("every attempt is recorded, not just the last one")
                .hasSize(delivery.maxAttempts());
    }

    @Test
    @DisplayName("4xx: a permanent client error is NOT retried")
    void clientErrorsAreNotRetried() {
        nextStatus = 400;
        createEndpointReturningSecret("https://ignored.test");
        long eventId = ingestOne();

        Delivery delivery = awaitStatus(eventId, DeliveryStatus.DEAD, Duration.ofSeconds(30));

        assertThat(HITS.get()).as("retrying a 400 eight times would be pure waste").isEqualTo(1);
        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(delivery.lastErrorClass()).isEqualTo("CLIENT_4XX");
    }

    @Test
    @DisplayName("a DEAD delivery can be replayed and then succeeds")
    void replayRecoversADeadDelivery() {
        nextStatus = 400;
        createEndpointReturningSecret("https://ignored.test");
        long eventId = ingestOne();
        Delivery dead = awaitStatus(eventId, DeliveryStatus.DEAD, Duration.ofSeconds(30));

        nextStatus = 200;
        deliveryService.replay(tenantId, dead.id());

        Delivery replayed = awaitStatus(eventId, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(30));
        assertThat(replayed.id()).isEqualTo(dead.id());
        assertThat(replayed.attemptCount())
                .as("replay adds one attempt rather than resetting the history")
                .isEqualTo(dead.attemptCount() + 1);
    }

    @Test
    @DisplayName("a paused endpoint does not delay the event: no delivery is created at all")
    void pausePreventsDelivery() {
        var created = endpointService.create(tenantId, "http://127.0.0.1:" + receiverPort + "/hook", "*", 8);
        endpointService.pause(tenantId, created.endpoint().id());

        long eventId = ingestOne();

        assertThat(deliveryMapper.count(tenantId, eventId, null)).isZero();
        assertThat(HITS.get()).isZero();
    }

    private long ingestOne() {
        return ingestService.ingest(tenantId, "order.paid", "{\"amount\":42,\"currency\":\"CNY\"}",
                "e2e-" + UUID.randomUUID()).eventId();
    }

    /** Creates an endpoint pointing at the in-process receiver, and remembers its secret. */
    private String createEndpointReturningSecret(String ignored) {
        var created = endpointService.create(tenantId,
                "http://127.0.0.1:" + receiverPort + "/hook", "*", 8);
        signingSecret = Base64.getUrlDecoder().decode(created.signingSecret());
        return created.signingSecret();
    }

    private Delivery awaitStatus(long eventId, DeliveryStatus expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            List<Delivery> deliveries = deliveryMapper.list(tenantId, eventId, null, 10, 0);
            if (!deliveries.isEmpty()) {
                latest = deliveryMapper.findById(deliveries.getFirst().id()).orElse(null);
                if (latest != null && latest.status() == expected) {
                    return latest;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String diagnosis = latest == null
                ? "no delivery created"
                : "state=" + latest.status()
                + " attemptCount=" + latest.attemptCount()
                + " maxAttempts=" + latest.maxAttempts()
                + " nextAttemptAt=" + latest.nextAttemptAt()
                + " lastErrorClass=" + latest.lastErrorClass()
                + " attemptRows=" + attemptMapper.listByDelivery(latest.id()).size();
        throw new AssertionError("Delivery for event " + eventId + " did not reach " + expected
                + " within " + timeout + "; " + diagnosis);
    }
}
