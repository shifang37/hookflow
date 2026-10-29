package dev.hookflow.service;

import dev.hookflow.api.dto.Dtos.EventSummaryResponse;
import dev.hookflow.api.dto.Dtos.FailureReasonResponse;
import dev.hookflow.api.dto.Dtos.LatencyBucketResponse;
import dev.hookflow.api.dto.Dtos.LatencyResponse;
import dev.hookflow.api.dto.Dtos.OverviewResponse;
import dev.hookflow.api.dto.Dtos.TenantResponse;
import dev.hookflow.api.dto.Dtos.TenantStatsResponse;
import dev.hookflow.api.dto.Dtos.TimeseriesPointResponse;
import dev.hookflow.api.dto.Dtos.TimeseriesResponse;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.persistence.ReportMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-model facade for the console and admin APIs.
 *
 * <p>This service owns presentation policy — page clamping, range parsing, how a
 * success rate is defined — and delegates every aggregate to {@link ReportMapper}.
 * Keeping that split explicit means the definition of "success rate" lives in one
 * Java method instead of being re-invented in a PromQL expression and again in the
 * frontend.
 *
 * <p>Nothing here writes. A read API that can mutate state is a read API nobody can
 * safely retry.
 */
@Service
public class ReportService {

    /** Same ceiling as the tenant-facing list endpoints, so a client cannot ask for 100k rows. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Accepted {@code range} values, in hours. Deliberately a closed set, not free-form. */
    private static final List<String> RANGES = List.of("24h", "7d", "30d", "90d");

    private static final String DEFAULT_RANGE = "7d";

    /** How many rows "top failure reasons" may return. */
    private static final int FAILURE_REASON_LIMIT = 5;

    /**
     * Bucket sizes a client may name explicitly, coarse to fine.
     *
     * <p>Starts at {@code 5m} rather than {@code 1m} on purpose: the shortest range the
     * API accepts is 24h, which at one-minute resolution is 1440 buckets — above
     * {@link #MAX_BUCKETS}. Offering an option that no valid request can use would just
     * be a documented 400.
     */
    public static final List<String> INTERVALS =
            List.of("5m", "15m", "30m", "1h", "3h", "6h", "12h", "1d");

    /** {@code auto} never produces a chart wider than this; more points than pixels is noise. */
    public static final int MAX_POINTS = 120;

    /**
     * Ceiling on the bucket grid, matched to MySQL's {@code cte_max_recursion_depth}
     * default of 1000: the grid is materialised by a recursive CTE, so a larger chart
     * would fail inside the database rather than being rejected here with a message
     * that says which knob to turn.
     */
    private static final int MAX_BUCKETS = 1000;

    /**
     * Latency histogram edges, in milliseconds. Pinned here and in {@link ReportMapper}
     * because the frontend derives bucket labels from them: changing the edges silently
     * would relabel every historical chart.
     */
    private static final int[] LATENCY_EDGES_MS = {50, 100, 250, 500, 1000, 2500, 5000, 10000};

    private final ReportMapper reportMapper;

    public ReportService(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    // ------------------------------------------------------------------ events

    public PageResponse<EventSummaryResponse> listEvents(long tenantId,
                                                         String eventType,
                                                         LocalDateTime from,
                                                         LocalDateTime to,
                                                         int page,
                                                         int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.invalid("from must be before to");
        }
        String type = blankToNull(eventType);
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        List<EventSummaryResponse> items = reportMapper
                .listEventSummaries(tenantId, type, from, to, safeSize, offset)
                .stream()
                .map(row -> new EventSummaryResponse(
                        row.eventId(), row.eventType(),
                        (int) row.deliveryCount(), (int) row.succeededCount(),
                        (int) row.failedCount(), (int) row.inFlightCount(),
                        row.createdAt()))
                .toList();
        long total = reportMapper.countEvents(tenantId, type, from, to);
        return PageResponse.of(items, safePage, safeSize, total);
    }

    // ----------------------------------------------------------------- tenants

    public PageResponse<TenantResponse> listTenants(String search, int page, int size) {
        String term = blankToNull(search);
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        List<TenantResponse> items = reportMapper.listTenants(term, safeSize, offset).stream()
                .map(ReportService::toTenantResponse)
                .toList();
        return PageResponse.of(items, safePage, safeSize, reportMapper.countTenants(term));
    }

    public TenantResponse tenant(long tenantId) {
        ReportMapper.TenantRow row = reportMapper.findTenant(tenantId);
        if (row == null) {
            throw ApiException.notFound("Tenant");
        }
        return toTenantResponse(row);
    }

    public TenantStatsResponse tenantStats(long tenantId, String range) {
        String label = normalizeRange(range);
        int hours = hoursOf(label);

        Map<String, Long> byStatus = countsByStatus(reportMapper.deliveryStatusCounts(tenantId, hours));
        long succeeded = byStatus.getOrDefault("SUCCEEDED", 0L);
        long dead = byStatus.getOrDefault("DEAD", 0L);
        long canceled = byStatus.getOrDefault("CANCELED", 0L);
        long deliveries = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long inWindow = byStatus.getOrDefault("PENDING", 0L)
                + byStatus.getOrDefault("DELIVERING", 0L)
                + byStatus.getOrDefault("RETRY_WAIT", 0L);

        List<FailureReasonResponse> reasons = failureReasons(tenantId, hours, dead);
        Long avgLatencyMs = toMillis(reportMapper.avgSettledLatencyMicros(tenantId, hours));

        return new TenantStatsResponse(
                tenantId, label,
                reportMapper.countEventsInWindow(tenantId, hours),
                deliveries, succeeded, dead, canceled, inWindow,
                successRate(succeeded, dead), avgLatencyMs,
                // Backlog is a *current* condition, so it is not windowed: an operator
                // needs to know what is stuck right now, not what was stuck a week ago.
                reportMapper.countInFlight(tenantId),
                reasons);
    }

    // ---------------------------------------------------------------- overview

    public OverviewResponse overview(String range) {
        String label = normalizeRange(range);
        int hours = hoursOf(label);

        Map<String, Long> byStatus = countsByStatus(reportMapper.deliveryStatusCounts(null, hours));
        long succeeded = byStatus.getOrDefault("SUCCEEDED", 0L);
        long dead = byStatus.getOrDefault("DEAD", 0L);
        long canceled = byStatus.getOrDefault("CANCELED", 0L);
        long deliveries = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long inWindow = byStatus.getOrDefault("PENDING", 0L)
                + byStatus.getOrDefault("DELIVERING", 0L)
                + byStatus.getOrDefault("RETRY_WAIT", 0L);

        return new OverviewResponse(
                label,
                reportMapper.countEventsInWindow(null, hours),
                deliveries, succeeded, dead, canceled, inWindow,
                successRate(succeeded, dead),
                toMillis(reportMapper.avgSettledLatencyMicros(null, hours)),
                reportMapper.countInFlight(null),
                reportMapper.countActiveTenants(),
                reportMapper.countActiveEndpoints());
    }

    // -------------------------------------------------------------- timeseries

    /**
     * Bucketed counts for the console charts.
     *
     * <p>Four round trips (one window anchor, three series) rather than one enormous
     * {@code UNION ALL}: each series is then a plain {@code GROUP BY} over one table,
     * which is the shape a database can plan well and a human can read.
     *
     * @param interval {@code auto} or one of {@link #INTERVALS}; {@code auto} picks the
     *                 finest interval that keeps the chart under {@link #MAX_POINTS}
     * @param tenantId null for the platform-wide chart
     */
    public TimeseriesResponse timeseries(String range, String interval, Long tenantId) {
        String label = normalizeRange(range);
        int hours = hoursOf(label);
        String resolved = resolveInterval(interval, hours);
        int bucketSeconds = intervalSeconds(resolved);
        int buckets = expectedBuckets(hours, bucketSeconds);
        if (buckets > MAX_BUCKETS) {
            throw ApiException.invalid("interval " + resolved + " needs " + buckets
                    + " buckets for range " + label + "; use a coarser interval");
        }

        ReportMapper.TimeWindow window = reportMapper.timeseriesWindow(bucketSeconds, buckets);

        Map<LocalDateTime, long[]> merged = new LinkedHashMap<>();
        for (ReportMapper.BucketCount row : reportMapper
                .eventBuckets(tenantId, window.fromAt(), window.toAt(), bucketSeconds)) {
            merged.computeIfAbsent(row.bucketStart(), start -> new long[5])[0] = row.total();
        }
        for (ReportMapper.BucketCount row : reportMapper
                .attemptBuckets(tenantId, window.fromAt(), window.toAt(), bucketSeconds)) {
            merged.computeIfAbsent(row.bucketStart(), start -> new long[5])[1] = row.total();
        }
        for (ReportMapper.SettlementBucket row : reportMapper
                .settlementBuckets(tenantId, window.fromAt(), window.toAt(), bucketSeconds)) {
            long[] counters = merged.computeIfAbsent(row.bucketStart(), start -> new long[5]);
            counters[2] = row.succeeded();
            counters[3] = row.dead();
            counters[4] = row.canceled();
        }

        List<TimeseriesPointResponse> points = merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> {
                    long[] counters = entry.getValue();
                    return new TimeseriesPointResponse(entry.getKey(),
                            counters[0], counters[1], counters[2], counters[3], counters[4]);
                })
                .toList();

        return new TimeseriesResponse(label, resolved, bucketSeconds,
                window.fromAt(), window.toAt(), points);
    }

    // ----------------------------------------------------------------- latency

    /**
     * Distribution and percentiles of individual HTTP attempts.
     *
     * <p>Read from {@code delivery_attempt}, not from a metrics registry: the platform
     * registers no timer today, so this is the only latency evidence that actually
     * exists. It answers "how slow is the receiver" far better than an average, and it
     * comes with a denominator ({@code samples}) so an empty window is distinguishable
     * from a fast one.
     */
    public LatencyResponse latency(String range, Long tenantId) {
        String label = normalizeRange(range);
        int hours = hoursOf(label);

        ReportMapper.LatencyStats stats = reportMapper.latencyStats(tenantId, hours);
        long samples = stats == null ? 0 : stats.samples();

        Map<Integer, Long> byEdge = new HashMap<>();
        long openEnded = 0;
        for (ReportMapper.LatencyBucketRow row : reportMapper.latencyHistogram(tenantId, hours)) {
            if (row.upperBoundMs() == null) {
                openEnded = row.total();
            } else {
                byEdge.merge(row.upperBoundMs(), row.total(), Long::sum);
            }
        }

        List<LatencyBucketResponse> buckets = new ArrayList<>(LATENCY_EDGES_MS.length + 1);
        for (int edge : LATENCY_EDGES_MS) {
            buckets.add(latencyBucket(edge, byEdge.getOrDefault(edge, 0L), samples));
        }
        // A delivery that timed out at the configured read timeout lands here; the
        // open-ended bucket would otherwise silently drop the slowest attempts.
        buckets.add(latencyBucket(null, openEnded, samples));

        return new LatencyResponse(label, samples,
                stats == null ? null : stats.p50Ms(),
                stats == null ? null : stats.p95Ms(),
                stats == null ? null : stats.p99Ms(),
                buckets);
    }

    private static LatencyBucketResponse latencyBucket(Integer upperBoundMs, long count, long samples) {
        return new LatencyBucketResponse(upperBoundMs, count,
                samples <= 0 ? 0.0 : round2(count * 100.0 / samples));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Trims and lower-cases a range, defaulting to {@code 7d}. Public and static so the
     * accepted vocabulary can be unit-tested without a database.
     */
    public static String normalizeRange(String range) {
        if (range == null || range.isBlank()) {
            return DEFAULT_RANGE;
        }
        return range.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws ApiException {@code INVALID_REQUEST} for anything outside the accepted set
     */
    public static int hoursOf(String normalizedRange) {
        return switch (normalizedRange) {
            case "24h" -> 24;
            case "7d" -> 24 * 7;
            case "30d" -> 24 * 30;
            case "90d" -> 24 * 90;
            default -> throw ApiException.invalid("range must be one of " + RANGES);
        };
    }

    /**
     * Success over <b>settled</b> deliveries only.
     *
     * <p>Deliveries still in flight are excluded rather than assumed to succeed: a
     * backlog of a million pending deliveries must not read as a 99.9% success rate.
     * With nothing settled yet the rate is 0.
     */
    public static double successRate(long succeeded, long dead) {
        long settled = succeeded + dead;
        if (settled <= 0) {
            return 0.0;
        }
        return round2(succeeded * 100.0 / settled);
    }

    /**
     * Resolves {@code auto} to a concrete interval, or validates an explicit one.
     *
     * @throws ApiException {@code INVALID_REQUEST} for anything not in {@link #INTERVALS}
     */
    public static String resolveInterval(String interval, int hours) {
        if (interval == null || interval.isBlank() || "auto".equalsIgnoreCase(interval.trim())) {
            return autoInterval(hours);
        }
        String normalized = interval.trim().toLowerCase(Locale.ROOT);
        if (!INTERVALS.contains(normalized)) {
            throw ApiException.invalid("interval must be auto or one of " + INTERVALS);
        }
        return normalized;
    }

    /**
     * The finest interval that keeps the chart within {@link #MAX_POINTS}.
     *
     * <p>A fixed interval per range would either waste pixels (1m over 90 days is 129 600
     * points) or throw away detail (1h over 24h is 24 points). The answer is part of the
     * response so the client never has to duplicate this rule to label an axis.
     */
    public static String autoInterval(int hours) {
        for (String candidate : INTERVALS) {
            if (expectedBuckets(hours, intervalSeconds(candidate)) <= MAX_POINTS) {
                return candidate;
            }
        }
        return INTERVALS.get(INTERVALS.size() - 1);
    }

    /**
     * How many points a chart of this range and interval will have.
     *
     * <p>Rounded up, and that ceiling is the contract: the client can size a buffer or
     * a header from it without duplicating the rounding rule, and the SQL grid is built
     * from the same number. Pure arithmetic — it touches no clock.
     */
    public static int expectedBuckets(int hours, int bucketSeconds) {
        long windowSeconds = hours * 3600L;
        return (int) ((windowSeconds + bucketSeconds - 1) / bucketSeconds);
    }

    /**
     * @throws ApiException {@code INVALID_REQUEST} for an unknown interval, so
     *                      {@link #autoInterval} cannot silently return a bogus size
     */
    public static int intervalSeconds(String interval) {
        return switch (interval) {
            case "5m" -> 300;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "3h" -> 10_800;
            case "6h" -> 21_600;
            case "12h" -> 43_200;
            case "1d" -> 86_400;
            default -> throw ApiException.invalid("interval must be auto or one of " + INTERVALS);
        };
    }

    private List<FailureReasonResponse> failureReasons(Long tenantId, int hours, long dead) {
        if (dead <= 0) {
            return List.of();
        }
        return reportMapper.topFailureReasons(tenantId, hours, FAILURE_REASON_LIMIT).stream()
                .map(row -> new FailureReasonResponse(
                        row.errorClass(), row.statusCode(), row.total(),
                        round2(row.total() * 100.0 / dead)))
                .toList();
    }

    private static Map<String, Long> countsByStatus(List<ReportMapper.StatusCount> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (ReportMapper.StatusCount row : rows) {
            if (row.status() != null) {
                counts.put(row.status(), row.total());
            }
        }
        return counts;
    }

    /** {@code null} in, {@code null} out: a window with no data has no average. */
    private static Long toMillis(Double micros) {
        return micros == null ? null : Math.round(micros / 1000.0);
    }

    private static TenantResponse toTenantResponse(ReportMapper.TenantRow row) {
        return new TenantResponse(row.tenantId(), row.name(), row.status(), row.rateLimit(),
                row.maxInFlight(), row.weight(), row.endpointCount(), row.activeKeyCount(),
                row.createdAt());
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
