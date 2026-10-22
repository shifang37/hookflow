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
    /** The healthy endpoint created for every test; tests that need another add one. */
    private long endpointId;

    @BeforeEach
    void setUp() {
        receiverDelayMs = 0;
        String name = "fault-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        endpointId = endpointService.create(tenantId, hookUrl(), "*", 8).endpoint().id();
    }

    @Test
    @DisplayName("a receiver slower than the read timeout is classified TIMEOUT and retried")
    void slowReceiverIsTimeoutAndRetried() {
        receiverDelayMs = 5_000;
        long eventId = ingestOne();

        Delivery delivery = awaitFailedAttempt(eventId, endpointId, Duration.ofSeconds(30));

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
        long refusedEndpointId = endpointService.create(tenantId, refusedUrl(), "*", 8).endpoint().id();
        long eventId = ingestOne();

        Delivery delivery = awaitFailedAttempt(eventId, refusedEndpointId, Duration.ofSeconds(30));

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(delivery.id());
        assertThat(attempts).isNotEmpty();
        assertThat(attempts.getFirst().errorClass())
                .as("connection refused is a transient network fault, not a client error "
                        + "(attempt status was %s)", attempts.getFirst().status())
                .isEqualTo("CONNECT_ERROR");
        assertThat(delivery.status()).isIn(DeliveryStatus.RETRY_WAIT, DeliveryStatus.DELIVERING);
    }

    @Test
    @DisplayName("when the receiver recovers, the SAME delivery succeeds without operator action")
    void recoversWithoutOperatorAction() {
        receiverDelayMs = 5_000;
        long eventId = ingestOne();

        Delivery afterFirstFailure = awaitFailedAttempt(eventId, endpointId, Duration.ofSeconds(30));
        assertThat(afterFirstFailure.status()).isEqualTo(DeliveryStatus.RETRY_WAIT);

        // The receiver is fixed. Nothing else is touched: no replay, no restart.
        receiverDelayMs = 0;

        Delivery recovered = awaitStatus(eventId, endpointId, DeliveryStatus.SUCCEEDED,
                Duration.ofSeconds(40));

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

        Delivery delivery = awaitStatus(eventId, endpointId, DeliveryStatus.SUCCEEDED,
                Duration.ofSeconds(30));

        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(attemptMapper.listByDelivery(delivery.id())).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private String hookUrl() {
        return "http://127.0.0.1:" + receiverPort + "/hook";
    }

    /**
     * A URL that refuses connections <b>deterministically</b>.
     *
     * <p>v0.2 bound port 0 to find a free port, closed the socket, and pointed the
     * endpoint at it. That is a race: the port comes from the ephemeral range, and
     * on a busy CI runner the OS can hand the same port to the next listener before
     * the delivery happens. The connection is then accepted, the attempt succeeds,
     * and the test asserts on a <em>successful</em> attempt
     * ({@code error_class = null}). It failed exactly like that in CI.
     *
     * <p>Port 1 is never bound by an unprivileged process, so "connection refused"
     * is a property of the address instead of a property of the moment.
     */
    private String refusedUrl() {
        return "http://127.0.0.1:1/hook";
    }

    private long ingestOne() {
        return ingestService.ingest(tenantId, "order.paid", "{\"amount\":7}",
                "fault-" + UUID.randomUUID()).eventId();
    }

    /**
     * Resolves the delivery for one (event, endpoint) pair.
     *
     * <p>Deliberately not "the newest delivery of the event": a tenant can have more
     * than one endpoint, the fan-out order is not defined, and picking the wrong
     * delivery is how this test lied about a healthy receiver.
     */
    private Delivery deliveryFor(long eventId, long endpointId) {
        return deliveryMapper.findByEventAndEndpoint(eventId, endpointId).orElse(null);
    }

    /**
     * Waits until this delivery has a <em>settled</em> attempt and is not mid-flight.
     *
     * <p>Both conditions are needed: the delivery can already be on its next attempt
     * while the row we would read still describes the previous one, and an attempt
     * row in {@code IN_PROGRESS} has a null error class by definition.
     */
    private Delivery awaitFailedAttempt(long eventId, long endpointId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = deliveryFor(eventId, endpointId);
            if (latest != null && latest.status() != DeliveryStatus.DELIVERING) {
                List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(latest.id());
                boolean settled = attempts.stream()
                        .anyMatch(attempt -> !DeliveryAttempt.IN_PROGRESS.equals(attempt.status()));
                if (settled) {
                    return latest;
                }
            }
            sleep(100);
        }
        throw new AssertionError("No settled attempt within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status())
                + "; attempts=" + (latest == null ? "none" : attemptMapper.listByDelivery(latest.id())));
    }

    private Delivery awaitStatus(long eventId, long endpointId, DeliveryStatus expected,
                                 Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = deliveryFor(eventId, endpointId);
            if (latest != null && latest.status() == expected) {
                return latest;
            }
            sleep(100);
        }
        throw new AssertionError("Did not reach " + expected + " within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status())
                + "; attempts=" + (latest == null ? "none" : attemptMapper.listByDelivery(latest.id())));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
