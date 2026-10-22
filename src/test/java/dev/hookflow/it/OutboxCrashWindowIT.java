package dev.hookflow.it;

import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.event.Event;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.messaging.DeliveryPublisher;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EventMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.relay.OutboxRelay;
import dev.hookflow.relay.OutboxSweeper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
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
 * The one duplicate that cannot be avoided: the relay published, the broker
 * confirmed, and the process died before the outbox row was marked.
 *
 * <p>This is the most important test of the whole at-least-once story, and it is
 * built <b>deterministically</b> instead of by racing a kill signal: the scenario
 * is reconstructed exactly as the crash would leave it (a `PUBLISHING` row whose
 * timestamp is already past the stale window, plus a message that really was
 * delivered to the broker), and then the recovery path is driven explicitly.
 *
 * <p>What it proves:
 * <ol>
 *   <li>{@link OutboxSweeper} re-arms the ambiguous row (data is not stranded),</li>
 *   <li>re-arming causes a <b>second</b> identical message,</li>
 *   <li>the worker's {@code attempt_count} fence absorbs it: the customer still sees
 *       exactly one attempt, and {@code attemptCount} stays 1.</li>
 * </ol>
 */
class OutboxCrashWindowIT extends AbstractIntegrationTest {

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
    private OutboxMapper outboxMapper;
    @Autowired
    private DeliveryPublisher publisher;
    @Autowired
    private OutboxSweeper sweeper;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;
    private long endpointId;

    @BeforeEach
    void setUp() {
        String name = "crash-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        // The receiver must accept, otherwise the test measures retries instead.
        endpointId = endpointService.create(tenantId, hookUrl(), "*", 8).endpoint().id();
    }

    @Test
    @DisplayName("crash after confirm: row re-armed, message duplicated, only one attempt reaches the receiver")
    void crashAfterConfirmIsRecoveredAndDuplicatesAreAbsorbed() {
        Scenario scenario = createScenarioLeavingOutboxInPublishing();

        // What actually happened before the crash: the broker received the message.
        DeliveryMessage alreadySent = new DeliveryMessage(scenario.deliveryId(), 1, tenantId,
                scenario.eventId(), endpointId, null);
        publisher.publish(alreadySent);

        // Recovery: the sweeper notices the ambiguous PUBLISHING row and re-arms it.
        sweeper.recoverStalePublishing();

        assertThat(outboxMapper.countUnpublished())
                .as("the stranded row must be queued again, not left in PUBLISHING")
                .isGreaterThanOrEqualTo(1);

        // Re-arming leads to a duplicate publish. Drive the relay regardless of its
        // own schedule so the test does not depend on timing.
        relay.relayOnce();

        Delivery done = awaitStatus(scenario.eventId(), DeliveryStatus.SUCCEEDED, Duration.ofSeconds(60));

        List<DeliveryAttempt> attempts = attemptMapper.listByDelivery(scenario.deliveryId());
        assertThat(attempts)
                .as("a duplicated message must not produce a second attempt")
                .hasSize(1);
        assertThat(attempts.getFirst().attemptNo()).isEqualTo(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryAttempt.SUCCEEDED);
        assertThat(done.attemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same message delivered twice results in one attempt")
    void duplicateMessageIsAbsorbedByTheAttemptFence() {
        Scenario scenario = createScenarioLeavingOutboxInPublishing();
        DeliveryMessage message = new DeliveryMessage(scenario.deliveryId(), 1, tenantId,
                scenario.eventId(), endpointId, null);

        // Mark the row published so only the two hand-published messages exist.
        jdbcTemplate.update("UPDATE outbox SET status = 'PUBLISHED', published_at = NOW(3) "
                + "WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ? AND attempt_no = 1",
                scenario.deliveryId());

        publisher.publish(message);
        publisher.publish(message);

        Delivery done = awaitStatus(scenario.eventId(), DeliveryStatus.SUCCEEDED, Duration.ofSeconds(60));

        // Give the second message time to be consumed and rejected.
        sleep(1_500);

        assertThat(attemptMapper.listByDelivery(scenario.deliveryId())).hasSize(1);
        assertThat(deliveryMapper.findById(scenario.deliveryId()).orElseThrow().attemptCount()).isEqualTo(1);
        assertThat(done.status()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- helpers

    private record Scenario(long eventId, long deliveryId) {
    }

    /**
     * Builds event + delivery by hand and leaves the outbox row in the exact state a
     * crash-after-confirm produces, without ever letting it be NEW. That removes any
     * race with the relay's own schedule.
     */
    private Scenario createScenarioLeavingOutboxInPublishing() {
        String key = "crash-" + UUID.randomUUID();
        eventMapper.insert(tenantId, "order.paid", "{\"amount\":13}", key);
        Event event = eventMapper.findByTenantAndIdempotencyKey(tenantId, key).orElseThrow();

        deliveryMapper.insert(tenantId, event.id(), endpointId, 8);
        Delivery delivery = deliveryMapper.findByEventAndEndpoint(event.id(), endpointId).orElseThrow();

        String payload = objectMapper.writeValueAsString(
                new DeliveryMessage(delivery.id(), 1, tenantId, event.id(), endpointId, null));
        jdbcTemplate.update("""
                        INSERT INTO outbox
                            (aggregate_type, aggregate_id, attempt_no, tenant_id, endpoint_id,
                             payload, status, publishing_since)
                        VALUES ('DELIVERY', ?, 1, ?, ?, ?, 'PUBLISHING', DATE_SUB(NOW(3), INTERVAL 120 SECOND))
                        """,
                delivery.id(), tenantId, endpointId, payload);

        return new Scenario(event.id(), delivery.id());
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
