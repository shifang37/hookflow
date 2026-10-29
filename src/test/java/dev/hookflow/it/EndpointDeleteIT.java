package dev.hookflow.it;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import dev.hookflow.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Endpoint deletion, against real MySQL: the parts a unit test with mocked mappers cannot
 * prove.
 *
 * <ul>
 *   <li>The {@code ck_endpoint_status} CHECK accepts the new {@code DELETED} value.</li>
 *   <li>Queued and retrying deliveries of that endpoint become {@code CANCELED}, and a
 *       different endpoint's deliveries are untouched.</li>
 *   <li>Every read path hides the row: {@code require} answers 404, the list and the count
 *       drop it, and fan-out no longer targets it.</li>
 *   <li>{@code uk_endpoint_tenant_url} releases the URL (the V5 generated column), so the
 *       normal reason to delete — recreate it with the right settings — actually works.</li>
 * </ul>
 *
 * <p>Deliveries are inserted directly instead of through ingest so the worker cannot finish
 * them before the assertion; the one test that does need fan-out calls ingest and asserts on
 * the delivery rows it produced. Rows are cleaned up afterwards: a PENDING delivery left
 * behind would be re-enqueued by {@code DeliveryRecovery} during a later test class.
 */
class EndpointDeleteIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenantId;
    private long doomedId;
    private long keptId;
    private String doomedUrl;

    @BeforeEach
    void setUp() {
        String name = "endpoint-delete-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();

        doomedUrl = "https://example.test/doomed-" + UUID.randomUUID();
        doomedId = endpointService.create(tenantId, doomedUrl, "*", 8).endpoint().id();
        keptId = endpointService.create(tenantId, "https://example.test/kept-" + UUID.randomUUID(),
                "*", 8).endpoint().id();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE a FROM delivery_attempt a
                  JOIN delivery d ON d.id = a.delivery_id
                 WHERE d.tenant_id = ?
                """, tenantId);
        jdbc.update("""
                DELETE o FROM outbox o
                  JOIN delivery d ON d.id = o.aggregate_id
                 WHERE o.aggregate_type = 'delivery' AND d.tenant_id = ?
                """, tenantId);
        jdbc.update("DELETE FROM delivery WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM endpoint WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("deleting cancels that endpoint's queued and retrying deliveries, and only those")
    void cancelsOnlyThatEndpointsQueue() {
        long eventId = newEvent("order.paid");
        long pending = insertDelivery(eventId, doomedId, "PENDING", "NOW(3)");
        long otherEventId = newEvent("order.paid");
        long retrying = insertDelivery(otherEventId, doomedId, "RETRY_WAIT", "NOW(3) + INTERVAL 1 DAY");
        long untouched = insertDelivery(eventId, keptId, "PENDING", "NOW(3)");

        endpointService.delete(tenantId, doomedId);

        assertThat(deliveryStatus(pending)).isEqualTo("CANCELED");
        assertThat(deliveryStatus(retrying)).isEqualTo("CANCELED");
        assertThat(deliveryStatus(untouched))
                .as("a delivery belonging to a different endpoint must not be touched")
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM endpoint WHERE id = ?", String.class, doomedId))
                .as("the row is kept for its delivery history, marked instead of removed")
                .isEqualTo(Endpoint.DELETED);
    }

    @Test
    @DisplayName("a deleted endpoint is gone from get, list, count and the tenant's endpoint count")
    void aDeletedEndpointIsGoneFromEveryReadPath() {
        endpointService.delete(tenantId, doomedId);

        assertThatThrownBy(() -> endpointService.require(tenantId, doomedId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(endpointService.list(tenantId, 0, 20))
                .extracting(Endpoint::id)
                .containsExactly(keptId);
        assertThat(endpointService.count(tenantId)).isEqualTo(1);
        assertThat(reportService.tenant(tenantId).endpointCount())
                .as("the console's tenant row must not count a deleted endpoint")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("deleting twice is a 404 the second time — the resource is gone, not hidden")
    void theSecondDeleteIsANotFound() {
        endpointService.delete(tenantId, doomedId);

        assertThatThrownBy(() -> endpointService.delete(tenantId, doomedId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("the URL becomes creatable again, which is the point of deleting it")
    void theUrlIsReleasedForReCreation() {
        endpointService.delete(tenantId, doomedId);

        long recreated = endpointService.create(tenantId, doomedUrl, "order.paid", 3).endpoint().id();

        assertThat(recreated).isNotEqualTo(doomedId);
        assertThat(jdbc.queryForObject("SELECT status FROM endpoint WHERE id = ?", String.class, recreated))
                .isEqualTo(Endpoint.ACTIVE);
        // Both rows still carry the URL; only the live one holds the unique key.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint WHERE tenant_id = ? AND url = ?", Integer.class,
                tenantId, doomedUrl)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint WHERE tenant_id = ? AND url = ? AND status <> 'DELETED'",
                Integer.class, tenantId, doomedUrl)).isEqualTo(1);
    }

    @Test
    @DisplayName("fan-out stops targeting the deleted endpoint")
    void fanOutSkipsTheDeletedEndpoint() {
        endpointService.delete(tenantId, doomedId);

        long eventId = ingestService
                .ingest(tenantId, "order.paid", "{\"amount\":1}", "fanout-" + UUID.randomUUID())
                .eventId();

        assertThat(jdbc.queryForList(
                "SELECT endpoint_id FROM delivery WHERE event_id = ? ORDER BY endpoint_id",
                Long.class, eventId))
                .containsExactly(keptId);
    }

    // ------------------------------------------------------------------ helpers

    private long newEvent(String eventType) {
        String key = "delete-it-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event (tenant_id, event_type, payload, idempotency_key)
                VALUES (?, ?, '{"amount":1}', ?)
                """, tenantId, eventType, key);
        return jdbc.queryForObject(
                "SELECT id FROM event WHERE tenant_id = ? AND idempotency_key = ?",
                Long.class, tenantId, key);
    }

    /**
     * Inserts a delivery without an outbox row, so no worker will claim it while the test
     * is asserting. {@code nextAttemptAt} is SQL, not a bound value, because the retry row
     * must be scheduled in the future and NOW(3) is the database's clock — the same rule the
     * production path follows.
     */
    private long insertDelivery(long eventId, long endpointId, String status, String nextAttemptAtSql) {
        jdbc.update("""
                INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count,
                                      max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, 0, 8, %s)
                """.formatted(nextAttemptAtSql), tenantId, eventId, endpointId, status);
        return jdbc.queryForObject(
                "SELECT id FROM delivery WHERE event_id = ? AND endpoint_id = ?",
                Long.class, eventId, endpointId);
    }

    private String deliveryStatus(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE id = ?", String.class, deliveryId);
    }
}
