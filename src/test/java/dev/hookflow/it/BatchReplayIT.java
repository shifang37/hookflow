package dev.hookflow.it;

import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.DeliveryService;
import dev.hookflow.service.EndpointService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch replay against real MySQL: the per-id transactions, the CAS, the outbox row and the
 * audit row are all database behaviour, and the point of the endpoint is what it does to a
 * <b>mixed</b> selection.
 *
 * <p>Deliveries are inserted directly (no outbox row) so they stay DEAD while the test runs;
 * after a replay the row is PENDING and the relay may publish it at any moment, so the
 * assertions are about facts a worker cannot change: the replay outcome it produced, the
 * raised {@code max_attempts}, and the rows that were written.
 */
class BatchReplayIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RetryOnLockConflict retryOnLockConflict;

    private long tenantId;
    private long otherTenantId;
    private long deadOne;
    private long deadTwo;
    private long succeeded;
    private long foreignDead;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("batch-replay");
        otherTenantId = newTenant("batch-replay-other");

        long first = endpointService.create(tenantId, "https://example.test/first", "*", 8)
                .endpoint().id();
        long second = endpointService.create(tenantId, "https://example.test/second", "*", 8)
                .endpoint().id();
        long otherEndpoint = endpointService.create(otherTenantId, "https://example.test/other", "*", 8)
                .endpoint().id();

        long fannedOut = newEvent(tenantId, "order.paid");
        long settled = newEvent(tenantId, "order.paid");
        long foreign = newEvent(otherTenantId, "order.paid");

        deadOne = insertDelivery(tenantId, fannedOut, first, "DEAD", 8);
        deadTwo = insertDelivery(tenantId, fannedOut, second, "DEAD", 8);
        succeeded = insertDelivery(tenantId, settled, first, "SUCCEEDED", 8);
        foreignDead = insertDelivery(otherTenantId, foreign, otherEndpoint, "DEAD", 8);
    }

    /**
     * Deletes the fixtures while the relay and worker are still live.
     *
     * <p>The old version deleted attempts, then outbox, then deliveries. A replay
     * leaves its deliveries PENDING with an outbox row, so the worker could claim one
     * and insert an IN_PROGRESS attempt <em>between</em> those statements; the final
     * {@code DELETE FROM delivery} then failed with an FK violation. Canceling first
     * closes the claim window (the claim CAS requires PENDING), and waiting for
     * in-flight attempts to settle closes the insert window, so children can be
     * deleted before parents deterministically. A lock conflict during the bulk
     * update is retried by the same component production uses.
     */
    @AfterEach
    void cleanUp() {
        for (long tenant : List.of(tenantId, otherTenantId)) {
            retryOnLockConflict.executeWithoutResult("it.batch-replay.cleanup",
                    () -> deleteTenantData(tenant));
        }
    }

    private void deleteTenantData(long tenant) {
        // No new claims after this commit: a worker's UPDATE ... WHERE status='PENDING'
        // matches nothing, and a row already claimed has its settle transition fenced.
        jdbc.update("""
                UPDATE delivery
                   SET status = 'CANCELED'
                 WHERE tenant_id = ?
                   AND status IN ('PENDING', 'RETRY_WAIT', 'DELIVERING')
                """, tenant);

        // Removing the messages that are still waiting to be published stops the relay
        // from handing the worker more work for this tenant.
        jdbc.update("DELETE FROM outbox WHERE tenant_id = ?", tenant);

        awaitNoInProgressAttempts(tenant);

        // A recovery tick that selected an outbox row just before the cancel can still
        // append one; the second delete is cheap and leaves no orphans behind.
        jdbc.update("DELETE FROM outbox WHERE tenant_id = ?", tenant);
        jdbc.update("""
                DELETE a FROM delivery_attempt a
                  JOIN delivery d ON d.id = a.delivery_id
                 WHERE d.tenant_id = ?
                """, tenant);
        jdbc.update("DELETE FROM delivery WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM event WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM endpoint WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
    }

    private void awaitNoInProgressAttempts(long tenant) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer inProgress = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM delivery_attempt a
                      JOIN delivery d ON d.id = a.delivery_id
                     WHERE d.tenant_id = ? AND a.status = 'IN_PROGRESS'
                    """, Integer.class, tenant);
            if (inProgress == null || inProgress == 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new IllegalStateException("tenant " + tenant
                + " still has an IN_PROGRESS attempt after 10s; refusing to delete under a live worker");
    }

    @Test
    @DisplayName("a mixed batch replays the DEAD ones, rejects the rest by id, and keeps the response ordered")
    void replaysOnlyDeadAndReportsEveryId() {
        List<DeliveryService.ReplayOutcome> outcomes = deliveryService.replayBatch(tenantId,
                List.of(deadOne, succeeded, foreignDead, deadTwo, 987_654_321L));

        assertThat(outcomes).hasSize(5);

        assertThat(outcomes.get(0).replayed()).isTrue();
        assertThat(outcomes.get(0).status()).isEqualTo("PENDING");

        assertThat(outcomes.get(1).replayed()).isFalse();
        assertThat(outcomes.get(1).rejection()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        assertThat(outcomes.get(1).message()).contains("SUCCEEDED");

        assertThat(outcomes.get(2).replayed())
                .as("another tenant's delivery must look missing, not forbidden")
                .isFalse();
        assertThat(outcomes.get(2).rejection()).isEqualTo(ErrorCode.NOT_FOUND);

        assertThat(outcomes.get(3).replayed()).isTrue();
        assertThat(outcomes.get(3).status()).isEqualTo("PENDING");

        assertThat(outcomes.get(4).replayed()).isFalse();
        assertThat(outcomes.get(4).rejection()).isEqualTo(ErrorCode.NOT_FOUND);

        // The replay grants exactly one more attempt; a worker settling the row does not
        // change max_attempts, so this is the deterministic half of the state transition.
        assertThat(maxAttempts(deadOne)).isEqualTo(9);
        assertThat(maxAttempts(deadTwo)).isEqualTo(9);
        assertThat(maxAttempts(succeeded)).as("a rejected id must not be modified").isEqualTo(8);

        assertThat(attempt9OutboxRows()).as("one outbox row per replayed delivery").isEqualTo(2);
        assertThat(replayAuditRows()).as("one audit row per replayed delivery").isEqualTo(2);
    }

    @Test
    @DisplayName("an empty batch is a no-op rather than an error, and touches nothing")
    void emptyBatchTouchesNothing() {
        assertThat(deliveryService.replayBatch(tenantId, List.of())).isEmpty();
        assertThat(maxAttempts(deadOne)).isEqualTo(8);
        assertThat(attempt9OutboxRows()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private long newTenant(String prefix) {
        String name = prefix + "-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        return tenantMapper.findByName(name).orElseThrow().id();
    }

    private long newEvent(long tenant, String eventType) {
        String key = "batch-replay-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event (tenant_id, event_type, payload, idempotency_key)
                VALUES (?, ?, '{"amount":1}', ?)
                """, tenant, eventType, key);
        return jdbc.queryForObject(
                "SELECT id FROM event WHERE tenant_id = ? AND idempotency_key = ?",
                Long.class, tenant, key);
    }

    private long insertDelivery(long tenant, long eventId, long endpointId, String status, int attempts) {
        jdbc.update("""
                INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenant, eventId, endpointId, status, attempts, attempts);
        return jdbc.queryForObject(
                "SELECT id FROM delivery WHERE event_id = ? AND endpoint_id = ?",
                Long.class, eventId, endpointId);
    }

    private int maxAttempts(long deliveryId) {
        return jdbc.queryForObject("SELECT max_attempts FROM delivery WHERE id = ?", Integer.class,
                deliveryId);
    }

    private int attempt9OutboxRows() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox o
                  JOIN delivery d ON d.id = o.aggregate_id
                 WHERE o.aggregate_type = 'delivery' AND o.attempt_no = 9 AND d.tenant_id = ?
                """, Integer.class, tenantId);
    }

    private int replayAuditRows() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                 WHERE tenant_id = ? AND action = 'delivery.replay'
                """, Integer.class, tenantId);
    }
}
