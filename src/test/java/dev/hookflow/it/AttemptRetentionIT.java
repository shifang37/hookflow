package dev.hookflow.it;

import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.worker.DeliveryAttemptRetention;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention sweeper against real MySQL.
 *
 * <p>Two things have to be true and neither can be shown with a mock:
 *
 * <ul>
 *   <li>old, <b>finished</b> rows really disappear (the DELETE's time arithmetic is
 *       done by MySQL, so a JVM-vs-database clock mismatch must not be able to make
 *       it delete the wrong rows);</li>
 *   <li>an old row that is still <b>IN_PROGRESS</b> survives. Deleting it would make
 *       {@code DeliveryProcessor.settle} silently update zero rows — this is the
 *       failure mode the whole design of the sweeper is built around.</li>
 * </ul>
 *
 * <p>The delivery is inserted directly rather than through ingest on purpose: a
 * directly inserted delivery has no outbox row, so the worker that runs in the
 * {@code all} profile never claims it and cannot insert attempt rows underneath the
 * assertions.
 */
class AttemptRetentionIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private DeliveryAttemptRetention retention;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MeterRegistry meterRegistry;

    private long tenantId;
    private long deliveryId;

    @BeforeEach
    void setUp() {
        String name = "it-retention-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        long endpointId = endpointService.create(tenantId, "https://retention.test/hook", "*", 8)
                .endpoint().id();

        String idempotencyKey = "retention-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event (tenant_id, event_type, payload, idempotency_key)
                VALUES (?, 'order.paid', '{"amount":1}', ?)
                """, tenantId, idempotencyKey);
        Long eventId = jdbc.queryForObject(
                "SELECT id FROM event WHERE tenant_id = ? AND idempotency_key = ?",
                Long.class, tenantId, idempotencyKey);
        jdbc.update("""
                INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts)
                VALUES (?, ?, ?, 'PENDING', 0, 8)
                """, tenantId, eventId, endpointId);
        deliveryId = jdbc.queryForObject(
                "SELECT id FROM delivery WHERE event_id = ? AND endpoint_id = ?",
                Long.class, eventId, endpointId);
    }

    @AfterEach
    void cleanUp() {
        // The delivery is PENDING with no outbox row, which is fine while the test
        // runs - but DeliveryRecovery scans for stuck PENDING rows on a 60s grace
        // window. Left behind, this delivery would be re-enqueued during a later IT
        // class, the worker would try to insert attempt 1 (the row this test already
        // created) and every later class would log a duplicate-key error. Deleting
        // the test's own rows keeps the shared database clean.
        jdbc.update("DELETE FROM delivery_attempt WHERE delivery_id = ?", deliveryId);
        jdbc.update("DELETE FROM delivery WHERE id = ?", deliveryId);
        jdbc.update("DELETE FROM event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM endpoint WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM api_key WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("old finished attempts are pruned, an old IN_PROGRESS attempt is not")
    void prunesOnlyFinishedAttemptsOutsideTheWindow() {
        long prunedBefore = prunedCount();

        // Three rows that are 30 days old: two finished (prunable) and one live
        // (must survive), plus one recent row that is not old enough to prune.
        insertAttempt(1, "SUCCEEDED", 30, true);
        insertAttempt(2, "FAILED", 30, true);
        insertAttempt(3, "IN_PROGRESS", 30, false);
        insertAttempt(4, "SUCCEEDED", 0, true);

        retention.prune();

        List<Integer> remaining = jdbc.queryForList(
                "SELECT attempt_no FROM delivery_attempt WHERE delivery_id = ? ORDER BY attempt_no",
                Integer.class, deliveryId);
        assertThat(remaining)
                .as("the two finished 30-day-old rows are gone; IN_PROGRESS and the recent row stay")
                .containsExactly(3, 4);

        assertThat(prunedCount() - prunedBefore)
                .as("hookflow.attempt.pruned counts what was actually deleted")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a live attempt is never deleted, however old its start time is")
    void liveAttemptSurvivesAnyAge() {
        insertAttempt(1, "IN_PROGRESS", 365, false);

        retention.prune();

        Integer attemptNo = jdbc.queryForObject(
                "SELECT attempt_no FROM delivery_attempt WHERE delivery_id = ?", Integer.class, deliveryId);
        assertThat(attemptNo)
                .as("deleting this row would turn the settle UPDATE into a silent no-op")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the V3 migration added the index the sweeper's WHERE clause needs")
    void retentionIndexExists() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'delivery_attempt'
                   AND index_name = 'idx_attempt_started'
                """, Integer.class);

        assertThat(count).as("V3__delivery_attempt_retention.sql must have run").isEqualTo(1);
    }

    @Test
    @DisplayName("a delivery accepted after pruning still settles, so pruning cannot strand live work")
    void pruneDoesNotBreakTheSettlePath() {
        // Exercise the same statement DeliveryProcessor uses to settle an attempt
        // after a pruning run: it must still find and update the row.
        insertAttempt(1, "IN_PROGRESS", 0, false);
        retention.prune();

        int updated = jdbc.update("""
                UPDATE delivery_attempt SET status = 'SUCCEEDED', finished_at = NOW(3)
                 WHERE delivery_id = ? AND attempt_no = 1 AND status = 'IN_PROGRESS'
                """, deliveryId);

        assertThat(updated).isEqualTo(1);
    }

    private void insertAttempt(int attemptNo, String status, int daysAgo, boolean finished) {
        jdbc.update("""
                INSERT INTO delivery_attempt (delivery_id, attempt_no, status, started_at, finished_at)
                VALUES (?, ?, ?, DATE_SUB(NOW(3), INTERVAL %d DAY), %s)
                """.formatted(daysAgo, finished ? "DATE_SUB(NOW(3), INTERVAL %d DAY)".formatted(daysAgo) : "NULL"),
                deliveryId, attemptNo, status);
    }

    private long prunedCount() {
        Counter counter = meterRegistry.find("hookflow.attempt.pruned").counter();
        return counter == null ? 0 : (long) counter.count();
    }
}
