package dev.hookflow.it;

import dev.hookflow.api.dto.Dtos.FailureReasonResponse;
import dev.hookflow.api.dto.Dtos.TenantStatsResponse;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The one report query that had never been run against real dead letters:
 * {@code ReportMapper.topFailureReasons}.
 *
 * <p>{@code ReadApiIT} only ever exercised the "no data, return an empty list" branch, so
 * the grouping, the percentage denominator and — the part a mocked mapper cannot show at
 * all — the {@code LIMIT} binding were unverified. The data here is written directly
 * because producing six distinct failure classes through the worker would mean six
 * different receiver misbehaviours and a slow, flaky test; what is under test is the SQL
 * over settled DEAD rows, not how they got that way.
 */
class FailureReasonsIT extends AbstractIntegrationTest {

    /** {@code ReportService.FAILURE_REASON_LIMIT}, restated: the test's job is to pin it. */
    private static final int LIMIT = 5;

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenantId;

    @BeforeEach
    void setUp() {
        String name = "failure-reasons-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM delivery WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM event WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM endpoint WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("dead deliveries are grouped by (error class, status code), with percentages of the dead total")
    void groupsAndPercentages() {
        deadDeliveries("SERVER_5XX", 500, 2);
        deadDeliveries("RETRYABLE_4XX", 429, 1);
        deadDeliveries("CLIENT_4XX", 404, 1);
        deadDeliveries("TIMEOUT", null, 1);

        TenantStatsResponse stats = reportService.tenantStats(tenantId, "7d");

        assertThat(stats.dead()).isEqualTo(5);
        assertThat(stats.successRate()).as("no settled success in the window").isZero();
        assertThat(stats.topFailureReasons()).hasSize(4);

        FailureReasonResponse top = stats.topFailureReasons().getFirst();
        assertThat(top.errorClass()).isEqualTo("SERVER_5XX");
        assertThat(top.statusCode()).isEqualTo(500);
        assertThat(top.count()).isEqualTo(2);
        assertThat(top.percentage()).isEqualTo(40.0);

        assertThat(stats.topFailureReasons())
                .extracting(FailureReasonResponse::errorClass)
                .containsExactly("SERVER_5XX", "RETRYABLE_4XX", "CLIENT_4XX", "TIMEOUT");

        // The same class with different status codes is two rows, not one: the console shows
        // "which code did the receiver return", and collapsing them would hide the 404s.
        deadDeliveries("SERVER_5XX", 502, 1);
        TenantStatsResponse grouped = reportService.tenantStats(tenantId, "7d");
        assertThat(grouped.topFailureReasons())
                .filteredOn(reason -> "SERVER_5XX".equals(reason.errorClass()))
                .extracting(FailureReasonResponse::statusCode)
                .containsExactlyInAnyOrder(500, 502);
    }

    @Test
    @DisplayName("only the top five reasons are returned, and the LIMIT really trims the tail")
    void capsTheListAndKeepsTheLargest() {
        // Counts 6,5,4,3,2,1 over six classes: 21 dead deliveries.
        String[] classes = {"A_CLASS", "B_CLASS", "C_CLASS", "D_CLASS", "E_CLASS", "F_CLASS"};
        for (int i = 0; i < classes.length; i++) {
            deadDeliveries(classes[i], 500 + i, classes.length - i);
        }

        TenantStatsResponse stats = reportService.tenantStats(tenantId, "7d");

        assertThat(stats.dead()).isEqualTo(21);
        assertThat(stats.topFailureReasons())
                .as("six classes, but the endpoint reports at most %d", LIMIT)
                .hasSize(LIMIT);
        assertThat(stats.topFailureReasons())
                .extracting(FailureReasonResponse::count)
                .as("largest first, and the count-1 class is the one dropped")
                .containsExactly(6L, 5L, 4L, 3L, 2L);
        assertThat(stats.topFailureReasons())
                .extracting(FailureReasonResponse::errorClass)
                .doesNotContain("F_CLASS");
        // The percentages use the *dead* total (21), not the sum of the five returned rows.
        assertThat(stats.topFailureReasons().getFirst().percentage())
                .isCloseTo(6 * 100.0 / 21, within(0.005));
    }

    // ------------------------------------------------------------------ helpers

    /** Inserts {@code count} DEAD deliveries for one (class, status code). */
    private void deadDeliveries(String errorClass, Integer statusCode, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count,
                                          max_attempts, last_error_class, last_status_code,
                                          created_at, updated_at)
                    VALUES (?, ?, ?, 'DEAD', 8, 8, ?, ?, NOW(3), NOW(3))
                    """, tenantId, nextEvent(), nextEndpoint(), errorClass, statusCode);
        }
    }

    /**
     * A fresh event per delivery, paired with a fresh endpoint: {@code uk_delivery_event_endpoint}
     * would reject the second row otherwise, and a test that had to work around its own
     * uniqueness key would stop testing the SQL and start testing the fixture.
     */
    private long nextEvent() {
        String key = "failure-reasons-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event (tenant_id, event_type, payload, idempotency_key)
                VALUES (?, 'order.paid', '{"amount":1}', ?)
                """, tenantId, key);
        return jdbc.queryForObject(
                "SELECT id FROM event WHERE tenant_id = ? AND idempotency_key = ?",
                Long.class, tenantId, key);
    }

    private long nextEndpoint() {
        return endpointService
                .create(tenantId, "https://example.test/failure-" + UUID.randomUUID(), "*", 8)
                .endpoint().id();
    }
}
