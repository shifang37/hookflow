package dev.hookflow.it;

import dev.hookflow.api.dto.Dtos.EventSummaryResponse;
import dev.hookflow.api.dto.Dtos.LatencyBucketResponse;
import dev.hookflow.api.dto.Dtos.LatencyResponse;
import dev.hookflow.api.dto.Dtos.OverviewResponse;
import dev.hookflow.api.dto.Dtos.TenantResponse;
import dev.hookflow.api.dto.Dtos.TenantStatsResponse;
import dev.hookflow.api.dto.Dtos.TimeseriesPointResponse;
import dev.hookflow.api.dto.Dtos.TimeseriesResponse;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.DeliveryService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import dev.hookflow.service.ReportService;
import dev.hookflow.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance criteria for the console read APIs: event stream roll-ups, tenant
 * statistics, platform overview, and the delivery filters the delivery table needs.
 *
 * <p>Assertions deliberately stick to facts that cannot change under the test: a
 * fresh tenant's event count and fan-out are deterministic, while how many of its
 * deliveries have succeeded by the time the assertion runs is not — the worker is
 * live in this profile. Tests that asserted on {@code succeededCount} would be the
 * flaky kind, and flaky tests get deleted rather than fixed.
 */
class ReadApiIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long allTypesEndpointId;
    private long refundsOnlyEndpointId;
    private long paidEventId;
    private long refundedEventId;

    @BeforeEach
    void setUp() {
        String name = "read-it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();

        allTypesEndpointId = endpointService
                .create(tenantId, "https://example.test/all", "*", 8).endpoint().id();
        refundsOnlyEndpointId = endpointService
                .create(tenantId, "https://example.test/refunds", "order.refunded", 8).endpoint().id();

        // order.paid fans out to one endpoint, order.refunded to both.
        paidEventId = ingestService
                .ingest(tenantId, "order.paid", "{\"amount\":42}", "paid-" + UUID.randomUUID())
                .eventId();
        refundedEventId = ingestService
                .ingest(tenantId, "order.refunded", "{\"amount\":7}", "refund-" + UUID.randomUUID())
                .eventId();
    }

    @Test
    @DisplayName("event stream: newest first, one delivery roll-up per event")
    void eventStreamRollsUpDeliveries() {
        PageResponse<EventSummaryResponse> page = reportService.listEvents(tenantId, null, null, null, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(EventSummaryResponse::eventId)
                .containsExactly(refundedEventId, paidEventId);

        EventSummaryResponse refunded = page.items().get(0);
        assertThat(refunded.eventType()).isEqualTo("order.refunded");
        assertThat(refunded.deliveryCount()).isEqualTo(2);
        assertThat(refunded.succeededCount() + refunded.failedCount() + refunded.inFlightCount())
                .as("the three counters partition deliveryCount")
                .isEqualTo(refunded.deliveryCount());

        EventSummaryResponse paid = page.items().get(1);
        assertThat(paid.deliveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("event stream filters by type and paginates")
    void eventStreamFiltersAndPaginates() {
        PageResponse<EventSummaryResponse> filtered =
                reportService.listEvents(tenantId, "order.paid", null, null, 0, 20);
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items()).singleElement()
                .satisfies(event -> assertThat(event.eventId()).isEqualTo(paidEventId));

        PageResponse<EventSummaryResponse> firstPage =
                reportService.listEvents(tenantId, null, null, null, 0, 1);
        PageResponse<EventSummaryResponse> secondPage =
                reportService.listEvents(tenantId, null, null, null, 1, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(firstPage.items().getFirst().eventId())
                .isNotEqualTo(secondPage.items().getFirst().eventId());
    }

    @Test
    @DisplayName("event stream honours a time window and rejects an inverted one")
    void eventStreamTimeWindow() {
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        assertThat(reportService.listEvents(tenantId, null, future, null, 0, 20).items()).isEmpty();

        assertThatThrownBy(() -> reportService.listEvents(tenantId, null, future, future.minusDays(2), 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("tenant page: counts are inline and search narrows the result")
    void tenantPage() {
        TenantResponse tenant = reportService.tenant(tenantId);
        assertThat(tenant.tenantId()).isEqualTo(tenantId);
        assertThat(tenant.endpointCount()).isEqualTo(2);
        assertThat(tenant.activeKeyCount()).isZero();

        String unique = tenant.name();
        PageResponse<TenantResponse> found = reportService.listTenants(unique, 0, 20);
        assertThat(found.total()).isEqualTo(1);
        assertThat(found.items()).singleElement()
                .satisfies(row -> assertThat(row.tenantId()).isEqualTo(tenantId));

        assertThat(reportService.listTenants(UUID.randomUUID().toString(), 0, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("tenant statistics: event and delivery totals match the fan-out")
    void tenantStatistics() {
        TenantStatsResponse stats = reportService.tenantStats(tenantId, "7d");

        assertThat(stats.tenantId()).isEqualTo(tenantId);
        assertThat(stats.range()).isEqualTo("7d");
        assertThat(stats.totalEvents()).isEqualTo(2);
        assertThat(stats.deliveries()).isEqualTo(3);
        assertThat(stats.successRate()).isBetween(0.0, 100.0);
        assertThat(stats.backlog()).isGreaterThanOrEqualTo(0);
        assertThat(stats.topFailureReasons().size()).isLessThanOrEqualTo(5);

        // The counters are a partition of the window's deliveries.
        assertThat(stats.succeeded() + stats.dead() + stats.canceled() + stats.inFlight())
                .isEqualTo(stats.deliveries());
    }

    @Test
    @DisplayName("statistics reject an unknown range instead of silently using a default")
    void statisticsRejectUnknownRange() {
        assertThatThrownBy(() -> reportService.tenantStats(tenantId, "1y"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("platform overview is readable without a tenant and has no negative counters")
    void platformOverview() {
        OverviewResponse overview = reportService.overview("24h");

        assertThat(overview.range()).isEqualTo("24h");
        assertThat(overview.activeTenants()).isGreaterThanOrEqualTo(1);
        assertThat(overview.activeEndpoints()).isGreaterThanOrEqualTo(2);
        assertThat(overview.totalEvents()).isGreaterThanOrEqualTo(2);
        assertThat(overview.successRate()).isBetween(0.0, 100.0);
        assertThat(overview.backlog()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("delivery list filters by endpoint and validates its status")
    void deliveryListFilters() {
        DeliveryService.Page page =
                deliveryService.list(tenantId, null, refundsOnlyEndpointId, null, null, null, 0, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(delivery -> assertThat(delivery.endpointId()).isEqualTo(refundsOnlyEndpointId));

        List<Delivery> all = deliveryService.list(tenantId, null, null, null, null, null, 0, 20).items();
        assertThat(all).hasSize(3);

        assertThatThrownBy(() -> deliveryService.list(tenantId, null, null, "NOT_A_STATUS", null, null, 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("tenant policy update is partial, validated, and reflected by the next read")
    void tenantPolicyUpdateIsPartialAndValidated() {
        TenantResponse before = reportService.tenant(tenantId);
        int originalMaxInFlight = before.maxInFlight();
        int originalWeight = before.weight();

        TenantResponse updated = reportService.tenant(tenantService.updateConfig(tenantId, 50, null, null).id());
        assertThat(updated.rateLimit()).isEqualTo(50);
        assertThat(updated.maxInFlight())
                .as("omitted fields keep their value")
                .isEqualTo(originalMaxInFlight);
        assertThat(updated.weight()).isEqualTo(originalWeight);

        assertThatThrownBy(() -> tenantService.updateConfig(tenantId, null, null, 101))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> tenantService.updateConfig(tenantId, null, null, null))
                .as("an update with no fields is a mistake, not a no-op")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tenantService.updateConfig(tenantId, -1, null, null))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------- timeseries

    @Test
    @DisplayName("timeseries: grid-aligned, zero-filled, and totals match what was ingested")
    void timeseriesIsAlignedAndZeroFilled() {
        TimeseriesResponse series = reportService.timeseries("7d", "auto", tenantId);

        assertThat(series.range()).isEqualTo("7d");
        assertThat(series.interval()).isEqualTo("3h");
        assertThat(series.bucketSeconds()).isEqualTo(10_800);
        assertThat(series.points()).isNotEmpty();

        // The point count is exactly the distance between the returned bounds: no
        // off-by-one, and no point outside the advertised window.
        long expectedPoints = Duration.between(series.from(), series.to()).toSeconds() / series.bucketSeconds();
        assertThat(series.points()).hasSize((int) expectedPoints);

        // Every point sits on the grid.
        for (TimeseriesPointResponse point : series.points()) {
            long offset = Duration.between(series.from(), point.bucketStart()).toSeconds();
            assertThat(offset % series.bucketSeconds())
                    .as("bucket %s is on the %s grid", point.bucketStart(), series.interval())
                    .isZero();
        }

        // Zero-filled: consecutive bucket starts differ by exactly one interval.
        for (int i = 1; i < series.points().size(); i++) {
            assertThat(Duration.between(series.points().get(i - 1).bucketStart(),
                    series.points().get(i).bucketStart()).toSeconds())
                    .as("bucket %d follows the previous one with no gap", i)
                    .isEqualTo(series.bucketSeconds());
        }

        long events = series.points().stream().mapToLong(TimeseriesPointResponse::events).sum();
        assertThat(events).as("both ingested events fall inside the window").isEqualTo(2);
        assertThat(series.points()).anySatisfy(point -> assertThat(point.events()).isPositive());
    }

    @Test
    @DisplayName("timeseries: an explicit interval is honoured and a too-fine one is rejected")
    void timeseriesIntervalValidation() {
        TimeseriesResponse hourly = reportService.timeseries("24h", "1h", tenantId);
        assertThat(hourly.interval()).isEqualTo("1h");
        assertThat(hourly.bucketSeconds()).isEqualTo(3600);
        assertThat(hourly.points())
                .as("exactly 24 buckets: the last one is the hour still being filled")
                .hasSize(24);

        assertThatThrownBy(() -> reportService.timeseries("90d", "5m", null))
                .as("25920 buckets would be materialised in SQL; refuse instead")
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));

        assertThatThrownBy(() -> reportService.timeseries("24h", "45s", tenantId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("timeseries: platform scope sees at least what one tenant does")
    void timeseriesPlatformScope() {
        TimeseriesResponse mine = reportService.timeseries("24h", "auto", tenantId);
        TimeseriesResponse platform = reportService.timeseries("24h", "auto", null);

        assertThat(mine.interval()).isEqualTo(platform.interval());
        long mineEvents = mine.points().stream().mapToLong(TimeseriesPointResponse::events).sum();
        long platformEvents = platform.points().stream().mapToLong(TimeseriesPointResponse::events).sum();
        assertThat(platformEvents).isGreaterThanOrEqualTo(mineEvents);
    }

    // ---------------------------------------------------------------- latency

    @Test
    @DisplayName("latency: buckets are complete, ordered, and add up to the sample count")
    void latencyHistogramIsComplete() {
        LatencyResponse latency = reportService.latency("24h", null);

        assertThat(latency.range()).isEqualTo("24h");
        assertThat(latency.buckets()).hasSize(9);
        assertThat(latency.buckets().getLast().upperBoundMs())
                .as("the final bucket is open-ended, so a timeout is never dropped")
                .isNull();

        Integer previousEdge = null;
        for (LatencyBucketResponse bucket : latency.buckets()) {
            if (bucket.upperBoundMs() != null) {
                if (previousEdge != null) {
                    assertThat(bucket.upperBoundMs()).isGreaterThan(previousEdge);
                }
                previousEdge = bucket.upperBoundMs();
            } else {
                assertThat(bucket).isSameAs(latency.buckets().getLast());
            }
            assertThat(bucket.count()).isGreaterThanOrEqualTo(0);
        }

        long counted = latency.buckets().stream().mapToLong(LatencyBucketResponse::count).sum();
        assertThat(counted).as("every sampled attempt lands in exactly one bucket")
                .isEqualTo(latency.samples());

        if (latency.samples() > 0) {
            assertThat(latency.p50Ms()).isNotNull();
            assertThat(latency.p50Ms()).isLessThanOrEqualTo(latency.p95Ms());
            assertThat(latency.p95Ms()).isLessThanOrEqualTo(latency.p99Ms());
            double percentages = latency.buckets().stream()
                    .mapToDouble(LatencyBucketResponse::percentage).sum();
            assertThat(percentages).isBetween(99.9, 100.1);
        } else {
            assertThat(latency.p50Ms()).isNull();
            assertThat(latency.buckets()).allSatisfy(bucket -> assertThat(bucket.count()).isZero());
        }
    }

    @Test
    @DisplayName("latency: a tenant with no traffic reads as unmeasured, not as fast")
    void latencyIsEmptyForAnIdleTenant() {
        String name = "idle-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        long idleTenant = tenantMapper.findByName(name).orElseThrow().id();

        LatencyResponse latency = reportService.latency("24h", idleTenant);

        assertThat(latency.samples()).isZero();
        assertThat(latency.p50Ms()).isNull();
        assertThat(latency.p95Ms()).isNull();
        assertThat(latency.p99Ms()).isNull();
        assertThat(latency.buckets()).hasSize(9);
        assertThat(latency.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.count()).isZero();
            assertThat(bucket.percentage()).isZero();
        });
    }

    // ----------------------------------------------------------------- indexes

    @Test
    @DisplayName("the settlement chart's predicate is supported by an index, in the right column order")
    void settlementIndexHasTheExpectedShape() {
        // Column order is the whole point: (status, updated_at) would not let the
        // platform-wide chart range-scan. A test that only asserted "an index exists"
        // would pass on the wrong shape, so assert the columns position by position.
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME
                  FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'delivery'
                   AND INDEX_NAME = 'idx_delivery_updated_status'
                 ORDER BY SEQ_IN_INDEX
                """, String.class);

        assertThat(columns).containsExactly("updated_at", "status");
    }
}
