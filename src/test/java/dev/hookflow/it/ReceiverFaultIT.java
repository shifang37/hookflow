package dev.hookflow.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receiver-side faults, injected deterministically: a receiver that answers too
 * slowly, one that refuses connections, and one that recovers mid-flight.
 *
 * <p>Each fault must map to a specific {@code error_class} and a specific retry
 * decision. These are exactly the behaviours a real customer endpoint produces,
 * and getting the classification wrong is what turns a small outage into a
 * retry storm or a silent permanent failure.
 */
class ReceiverFaultIT extends AbstractIntegrationTest {

    /** Delay the in-test receiver applies before answering. 0 = healthy. */
    private static volatile long receiverDelayMs = 0;

    private static HttpServer receiver;
    private static int receiverPort;

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(Executors.newFixedThreadPool(8));
        receiver.createContext("/hook", exchange -> {
            long delay = receiverDelayMs;
            if (delay > 0) {
                sleep(delay);
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
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

    private long tenantId;

    @BeforeEach
    void setUp() {
        receiverDelayMs = 0;
        String name = "fault-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        endpointService.create(tenantId, hookUrl(), "*", 8);
    }

    @Test
    @DisplayName("a receiver slower than the read timeout is classified TIMEOUT and retried")
    void slowReceiverIsTimeoutAndRetried() {
        receiverDelayMs = 5_000;
        long eventId = ingestOne();

        Delivery delivery = awaitFailedAttempt(eventId, Duration.ofSeconds(30));

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(delivery.id());
        assertThat(attempts).isNotEmpty();
        assertThat(attempts.getFirst().errorClass())
                .as("a read timeout must be classified, not lumped into a generic failure")
                .isEqualTo("TIMEOUT");
        assertThat(attempts.getFirst().statusCode()).isNull();
        assertThat(delivery.lastErrorClass()).isEqualTo("TIMEOUT");
        assertThat(delivery.status())
                .as("a timeout is transient, so the delivery waits to retry instead of dying")
                .isIn(DeliveryStatus.RETRY_WAIT, DeliveryStatus.DELIVERING);
    }

    @Test
    @DisplayName("a refused connection is classified CONNECT_ERROR and retried")
    void refusedConnectionIsConnectError() {
        int closedPort = findClosedPort();
        endpointService.create(tenantId, "http://127.0.0.1:" + closedPort + "/hook", "*", 8);
        long eventId = ingestOne();

        Delivery delivery = awaitFailedAttempt(eventId, Duration.ofSeconds(30));

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(delivery.id());
        assertThat(attempts).isNotEmpty();
        assertThat(attempts.getFirst().errorClass())
                .as("connection refused is a transient network fault, not a client error")
                .isEqualTo("CONNECT_ERROR");
        assertThat(delivery.status()).isIn(DeliveryStatus.RETRY_WAIT, DeliveryStatus.DELIVERING);
    }

    @Test
    @DisplayName("when the receiver recovers, the SAME delivery succeeds without operator action")
    void recoversWithoutOperatorAction() {
        receiverDelayMs = 5_000;
        long eventId = ingestOne();

        Delivery afterFirstFailure = awaitFailedAttempt(eventId, Duration.ofSeconds(30));
        assertThat(afterFirstFailure.status()).isEqualTo(DeliveryStatus.RETRY_WAIT);

        // The receiver is fixed. Nothing else is touched: no replay, no restart.
        receiverDelayMs = 0;

        Delivery recovered = awaitStatus(eventId, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(40));

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(recovered.id());
        assertThat(attempts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(attempts.getFirst().errorClass()).isEqualTo("TIMEOUT");
        assertThat(attempts.getLast().status()).isEqualTo(DeliveryAttempt.SUCCEEDED);
        assertThat(recovered.attemptCount())
                .as("attempt counter matches the recorded history")
                .isEqualTo(attempts.size());
    }

    @Test
    @DisplayName("a healthy receiver is never retried")
    void healthyReceiverIsNotRetried() {
        long eventId = ingestOne();

        Delivery delivery = awaitStatus(eventId, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(30));

        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(attemptMapper.listByDelivery(delivery.id())).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private String hookUrl() {
        return "http://127.0.0.1:" + receiverPort + "/hook";
    }

    private long ingestOne() {
        return ingestService.ingest(tenantId, "order.paid", "{\"amount\":7}",
                "fault-" + UUID.randomUUID()).eventId();
    }

    /** Waits until at least one attempt is recorded and the delivery is not mid-flight. */
    private Delivery awaitFailedAttempt(long eventId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            List<Delivery> deliveries = deliveryMapper.list(tenantId, eventId, null, 10, 0);
            if (!deliveries.isEmpty()) {
                latest = deliveryMapper.findById(deliveries.getFirst().id()).orElse(null);
                if (latest != null
                        && latest.status() != DeliveryStatus.DELIVERING
                        && !attemptMapper.listByDelivery(latest.id()).isEmpty()) {
                    return latest;
                }
            }
            sleep(100);
        }
        throw new AssertionError("No settled attempt within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status()));
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
            sleep(100);
        }
        throw new AssertionError("Did not reach " + expected + " within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status()));
    }

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
