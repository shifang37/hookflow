package dev.hookflow.it;

import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline claim in the README: <b>the broker is not the source of truth</b>.
 *
 * <p>This test stops the RabbitMQ container mid-flight and asserts, in order:
 * <ol>
 *   <li>events are still accepted and durably stored (MySQL only),</li>
 *   <li>nothing reaches the customer endpoint while the broker is down,</li>
 *   <li>work parks in the outbox rather than disappearing,</li>
 *   <li>after the broker comes back the backlog drains and the delivery succeeds
 *       with exactly one attempt and no operator action.</li>
 * </ol>
 *
 * <p><b>Why a real container stop instead of a network proxy:</b> the first version
 * of this test used Toxiproxy in front of RabbitMQ. That requires the proxy and the
 * broker to share a Docker network, and on Docker Desktop the proxy accepted the TCP
 * connection while being unable to reach its upstream — the client saw an AMQP
 * handshake timeout and the failure looked like an application bug. Stopping and
 * starting the container exercises the same property (broker unavailable, then
 * available) with fewer moving parts and identical semantics, and it also proves the
 * client's own reconnection logic. See {@code docs/adr/0008-fault-injection-tooling.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"all", "test"})
class BrokerOutageIT {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("hookflow")
                    .withUsername("hookflow")
                    .withPassword("hookflow");

    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4-management");

    /** A receiver that always answers 200, so "delivered" is distinguishable from "died". */
    private static com.sun.net.httpserver.HttpServer receiver;
    private static int receiverPort;

    static {
        MYSQL.start();
        RABBITMQ.start();
        try {
            receiver = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            receiver.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            receiver.createContext("/hook", exchange -> {
                byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            receiver.start();
            receiverPort = receiver.getAddress().getPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to start the in-test receiver", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (receiver != null) {
                receiver.stop(0);
            }
            MYSQL.stop();
            RABBITMQ.stop();
        }));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("hookflow.rate-limit.enabled", () -> false);
        registry.add("hookflow.retry.base-delay-ms", () -> 200);
        registry.add("hookflow.retry.scan-interval-ms", () -> 200);
        registry.add("hookflow.relay.interval-ms", () -> 100);
        registry.add("hookflow.lease.sweep-interval-ms", () -> 3_600_000);
        registry.add("hookflow.delivery.read-timeout-ms", () -> 1_500);
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
    private OutboxMapper outboxMapper;

    private long tenantId;

    @BeforeEach
    void setUp() {
        String name = "outage-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        endpointService.create(tenantId, "http://127.0.0.1:" + receiverPort + "/hook", "*", 8);
    }

    @Test
    @DisplayName("broker down: events still accepted, nothing lost, recovery automatic")
    void brokerOutageDoesNotLoseEvents() {
        // --------------------------------------------------------- 1. baseline
        long healthyEvent = ingestOne();
        awaitStatus(healthyEvent, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(60));
        assertThat(outboxMapper.countUnpublished()).isZero();

        // -------------------------------------------------- 2. freeze the broker
        // `pause` keeps the container's published port mapping intact, so the
        // client keeps dialling the same address and simply cannot complete the
        // handshake — which is exactly what a hung broker or a network partition
        // looks like. Stopping and starting the container would remap the port and
        // turn this into a misconfiguration test instead.
        pauseBroker();

        long eventDuringOutage = ingestOne();

        Delivery waiting = awaitAnyDelivery(eventDuringOutage, Duration.ofSeconds(15));
        assertThat(waiting.status())
                .as("with the broker down a delivery can only be queued")
                .isIn(DeliveryStatus.PENDING, DeliveryStatus.DELIVERING);
        assertThat(deliveryMapper.count(tenantId, eventDuringOutage, null))
                .as("the accepted event has a durable delivery row")
                .isEqualTo(1);

        // Let the relay try (and fail) to publish a few times.
        sleep(5_000);
        Delivery stillWaiting = deliveryMapper.findById(waiting.id()).orElseThrow();
        assertThat(stillWaiting.status())
                .as("no attempt may be recorded while the broker is unreachable")
                .isIn(DeliveryStatus.PENDING, DeliveryStatus.DELIVERING);
        assertThat(attemptMapper.listByDelivery(waiting.id())).isEmpty();
        assertThat(outboxMapper.countUnpublished())
                .as("work is parked in the outbox, not lost "
                        + "(NEW alone would read 0 because rows cycle through PUBLISHING)")
                .isGreaterThanOrEqualTo(1);

        // ------------------------------------------- 3. bring the broker back
        unpauseBroker();

        Delivery recovered = awaitStatus(eventDuringOutage, DeliveryStatus.SUCCEEDED, Duration.ofSeconds(120));
        assertThat(recovered.id()).isEqualTo(waiting.id());
        assertThat(recovered.attemptCount())
                .as("exactly one attempt was needed once the broker returned")
                .isEqualTo(1);
        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(recovered.id());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryAttempt.SUCCEEDED);
    }

    // ---------------------------------------------------------------- helpers

    private static void pauseBroker() {
        RABBITMQ.getDockerClient().pauseContainerCmd(RABBITMQ.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        RABBITMQ.getDockerClient().unpauseContainerCmd(RABBITMQ.getContainerId()).exec();
    }

    private long ingestOne() {
        return ingestService.ingest(tenantId, "order.paid", "{\"amount\":11}",
                "outage-" + UUID.randomUUID()).eventId();
    }

    private Delivery awaitAnyDelivery(long eventId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Delivery> deliveries = deliveryMapper.list(tenantId, eventId, null, 10, 0);
            if (!deliveries.isEmpty()) {
                return deliveries.getFirst();
            }
            sleep(200);
        }
        throw new AssertionError("No delivery row was created for event " + eventId);
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
            sleep(200);
        }
        throw new AssertionError("Did not reach " + expected + " within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status())
                + " unpublishedOutbox=" + outboxMapper.countUnpublished());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
