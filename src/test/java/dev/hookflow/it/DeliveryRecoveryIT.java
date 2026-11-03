package dev.hookflow.it;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.event.Event;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EventMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.relay.OutboxRelay;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.OutboxWriter;
import dev.hookflow.worker.DeliveryRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The third recovery path, the one drill A3 (failures/0008 §7) left without an
 * automated test: a broker message is lost after the relay saw it confirmed, so
 * the delivery sits in PENDING forever even though its outbox row says PUBLISHED.
 *
 * <p>{@link DeliveryRecovery#requeueStuckPending()} finds PENDING rows past the
 * grace window, {@link OutboxWriter#append} then hits the unique key of the old
 * attempt, and {@code OutboxMapper.rearmStalePublished} re-arms the stale
 * PUBLISHED row. The relay republishes it and the worker's attempt-count fence
 * still yields exactly one attempt.
 *
 * <p>The lost state is reconstructed deterministically - the same choice
 * {@code OutboxCrashWindowIT} documents - instead of racing a real
 * {@code rabbitmqctl purge_queue} against the 100 ms test relay: "the broker
 * accepted the message and nothing ever consumed it" is exactly an outbox row in
 * PUBLISHED whose delivery is PENDING, regardless of whether the loss came from
 * a purge, a crash or a nack. The end-to-end variant with a real queue purge
 * stays in drill A3.
 *
 * <p>{@code AbstractIntegrationTest} raises the scheduled scan's interval out of
 * the way, as it already does for the lease sweeper; these tests drive
 * {@code requeueStuckPending()} explicitly.
 */
class DeliveryRecoveryIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventMapper eventMapper;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private DeliveryAttemptMapper attemptMapper;
    @Autowired
    private OutboxWriter outboxWriter;
    @Autowired
    private DeliveryRecovery recovery;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private HookFlowProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;
    private long endpointId;

    @BeforeEach
    void setUp() {
        String name = "recovery-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        // The receiver must accept, otherwise the test measures retries instead.
        endpointId = endpointService.create(tenantId, hookUrl(), "*", 8).endpoint().id();
    }

    @AfterEach
    void cleanUp() {
        // Same reasoning as AttemptRetentionIT: a PENDING delivery left behind is
        // exactly what a later recovery scan (or a later test's explicit one) would
        // pick up; rows belong to the test that created them.
        jdbc.update("DELETE FROM delivery_attempt WHERE delivery_id IN "
                + "(SELECT id FROM delivery WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM outbox WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM delivery WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM endpoint WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM api_key WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("lost broker message -> re-armed past the grace window -> one attempt, SUCCEEDED")
    void lostBrokerMessageIsReArmedAndDeliveredExactlyOnce() {
        Scenario scenario = buildScenario(120);
        int grace = properties.relay().stalePublishingSeconds();

        assertThat(stuckIds(grace))
                .as("a fresh PENDING delivery is inside the grace window and must not be touched")
                .doesNotContain(scenario.deliveryId());

        // The message was accepted by the broker long ago and then lost: nothing
        // will ever consume it, so the delivery ages out of the grace window.
        jdbc.update("UPDATE delivery SET updated_at = DATE_SUB(NOW(3), INTERVAL 120 SECOND) "
                + "WHERE id = ?", scenario.deliveryId());
        assertThat(stuckIds(grace))
                .as("past the grace window the delivery is exactly what the scan looks for")
                .contains(scenario.deliveryId());

        double requeuesBefore = meterRegistry
                .counter("hookflow.recovery.stuck_requeued").count();

        recovery.requeueStuckPending();

        assertThat(stalePublishedCount(scenario.deliveryId(), grace))
                .as("append() must have re-armed the stale PUBLISHED row, not inserted a second one")
                .isZero();

        // Drive the relay regardless of its own schedule so the test does not
        // depend on timing; the republished message is what lets the worker finish.
        relay.relayOnce();

        Delivery done = awaitStatus(scenario.eventId(), DeliveryStatus.SUCCEEDED,
                Duration.ofSeconds(60));
        assertThat(done.attemptCount())
                .as("the attempt fence absorbs any duplicate publish")
                .isEqualTo(1);

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(scenario.deliveryId());
        assertThat(attempts)
                .as("a recovered delivery must not show extra attempts")
                .hasSize(1);
        assertThat(attempts.getFirst().attemptNo()).isEqualTo(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryAttempt.SUCCEEDED);

        assertThat(meterRegistry.counter("hookflow.recovery.stuck_requeued").count())
                .as("the requeue must be visible in the recovery metric")
                .isGreaterThanOrEqualTo(requeuesBefore + 1);

        assertThat(isFreshlyPublished(scenario.deliveryId()))
                .as("the relay really republished the re-armed row")
                .isTrue();
    }

    @Test
    @DisplayName("a PUBLISHED row inside the grace window must not be re-armed")
    void freshPublishedRowIsNotReArmed() {
        Scenario scenario = buildScenario(0);
        int grace = properties.relay().stalePublishingSeconds();

        assertThat(stuckIds(grace))
                .as("PENDING but updated_at is fresh, so the scan must skip it")
                .doesNotContain(scenario.deliveryId());

        // The scan finds nothing for this row...
        recovery.requeueStuckPending();
        // ...and even a direct append cannot touch an attempt whose publish is
        // inside the grace window: that message may still be on its way.
        assertThat(outboxWriter.append(tenantId, scenario.eventId(), endpointId,
                scenario.deliveryId(), 1))
                .as("re-arming a fresh PUBLISHED row would weaken the uniqueness contract")
                .isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox WHERE aggregate_type = 'DELIVERY' "
                        + "AND aggregate_id = ? AND attempt_no = 1",
                String.class, scenario.deliveryId()))
                .isEqualTo("PUBLISHED");
        assertThat(deliveryMapper.findById(scenario.deliveryId()).orElseThrow().status())
                .isEqualTo(DeliveryStatus.PENDING);
        assertThat(attemptMapper.listByDelivery(scenario.deliveryId())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private record Scenario(long eventId, long deliveryId) {
    }

    /**
     * Leaves behind the exact state a lost broker message produces: real event and
     * delivery rows, an outbox row claiming the broker accepted attempt 1
     * {@code publishedAgeSeconds} ago, and no message anywhere on the broker.
     */
    private Scenario buildScenario(int publishedAgeSeconds) {
        String key = "lost-" + UUID.randomUUID();
        eventMapper.insert(tenantId, "order.paid", "{\"amount\":42}", key);
        Event event = eventMapper.findByTenantAndIdempotencyKey(tenantId, key).orElseThrow();

        deliveryMapper.insert(tenantId, event.id(), endpointId, 8);
        Delivery delivery = deliveryMapper.findByEventAndEndpoint(event.id(), endpointId).orElseThrow();

        String payload = objectMapper.writeValueAsString(
                new DeliveryMessage(delivery.id(), 1, tenantId, event.id(), endpointId, null));
        jdbc.update("""
                        INSERT INTO outbox
                            (aggregate_type, aggregate_id, attempt_no, tenant_id, endpoint_id,
                             payload, status, published_at)
                        VALUES ('DELIVERY', ?, 1, ?, ?, ?, 'PUBLISHED', DATE_SUB(NOW(3), INTERVAL ? SECOND))
                        """,
                delivery.id(), tenantId, endpointId, payload, publishedAgeSeconds);

        return new Scenario(event.id(), delivery.id());
    }

    private List<Long> stuckIds(int graceSeconds) {
        return deliveryMapper.findStuckPending(graceSeconds, 200).stream()
                .map(Delivery::id)
                .toList();
    }

    /** Rows that are PUBLISHED and older than the grace window: what append() re-arms. */
    private int stalePublishedCount(long deliveryId, int graceSeconds) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox
                 WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ? AND attempt_no = 1
                   AND status = 'PUBLISHED'
                   AND published_at < DATE_SUB(NOW(3), INTERVAL ? SECOND)
                """, Integer.class, deliveryId, graceSeconds);
        return count == null ? 0 : count;
    }

    private boolean isFreshlyPublished(long deliveryId) {
        Boolean fresh = jdbc.queryForObject("""
                SELECT status = 'PUBLISHED'
                   AND published_at >= DATE_SUB(NOW(3), INTERVAL 60 SECOND)
                  FROM outbox
                 WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ? AND attempt_no = 1
                """, Boolean.class, deliveryId);
        return Boolean.TRUE.equals(fresh);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + SharedReceiver.port() + "/hook";
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
