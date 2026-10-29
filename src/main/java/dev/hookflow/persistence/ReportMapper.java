package dev.hookflow.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-model queries behind the console and admin APIs.
 *
 * <p>Kept out of {@link DeliveryMapper} for the same reason {@code DemoMapper} is:
 * the delivery mappers are the state machine, where every statement is a
 * compare-and-set a test can rely on. These are aggregates — {@code COUNT},
 * {@code GROUP BY}, {@code AVG} — and a reporting query that silently changed a
 * state transition would be a very unpleasant surprise.
 *
 * <p><b>Time windows are computed by MySQL, never by the JVM.</b> {@code NOW(3) -
 * INTERVAL ? HOUR} keeps every compared value on the database clock, so a JVM in a
 * different timezone cannot shift a window by hours — see
 * {@code docs/failures/0002-two-clocks.md}. Window parameters are integer hours,
 * not timestamps, on purpose.
 *
 * <p>Every method that takes {@code tenantId} treats {@code null} as "all tenants",
 * so the tenant page and the platform overview run the same SQL instead of two
 * copies that can drift apart.
 */
@Mapper
public interface ReportMapper {

    /**
     * One row per event with its delivery counters, for the event stream table.
     *
     * <p>The counters come from a single {@code LEFT JOIN ... GROUP BY} instead of N
     * follow-up queries: a page of 20 events must cost one round trip, not 21.
     * {@code COALESCE} is required because an event with no delivery at all still
     * produces one joined row whose aggregates are {@code NULL}, and {@code SUM}
     * ignores those ({@code SUM(NULL)} is {@code NULL}, not {@code 0}).
     */
    record EventSummaryRow(
            Long eventId,
            String eventType,
            LocalDateTime createdAt,
            long deliveryCount,
            long succeededCount,
            long failedCount,
            long inFlightCount) {
    }

    record StatusCount(String status, long total) {
    }

    /** {@code errorClass} may be null when an attempt failed without a classification. */
    record FailureReasonRow(String errorClass, Integer statusCode, long total) {
    }

    /**
     * A tenant plus the two counts the admin table shows.
     *
     * <p>The counts are correlated subqueries rather than two extra round trips:
     * a tenant page is 20 rows, so 40 small index lookups on
     * {@code idx_api_key_tenant} / {@code idx_endpoint_tenant_status} are cheaper
     * than two more statements and a join in the application.
     */
    record TenantRow(
            Long tenantId,
            String name,
            String status,
            int rateLimit,
            int maxInFlight,
            int weight,
            LocalDateTime createdAt,
            long endpointCount,
            long activeKeyCount) {
    }

    @Select("""
            <script>
            SELECT e.id                         AS eventId,
                   e.event_type                 AS eventType,
                   e.created_at                 AS createdAt,
                   COUNT(d.id)                  AS deliveryCount,
                   COALESCE(SUM(d.status = 'SUCCEEDED'), 0)                        AS succeededCount,
                   COALESCE(SUM(d.status IN ('DEAD', 'CANCELED')), 0)              AS failedCount,
                   COALESCE(SUM(d.status IN ('PENDING', 'DELIVERING', 'RETRY_WAIT')), 0) AS inFlightCount
              FROM event e
              LEFT JOIN delivery d ON d.event_id = e.id
             WHERE e.tenant_id = #{tenantId}
             <if test='eventType != null'> AND e.event_type = #{eventType}</if>
             <if test='fromTime != null'> AND e.created_at &gt;= #{fromTime}</if>
             <if test='toTime != null'> AND e.created_at &lt; #{toTime}</if>
             GROUP BY e.id, e.event_type, e.created_at
             ORDER BY e.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EventSummaryRow> listEventSummaries(@Param("tenantId") long tenantId,
                                             @Param("eventType") String eventType,
                                             @Param("fromTime") LocalDateTime fromTime,
                                             @Param("toTime") LocalDateTime toTime,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM event e
             WHERE e.tenant_id = #{tenantId}
             <if test='eventType != null'> AND e.event_type = #{eventType}</if>
             <if test='fromTime != null'> AND e.created_at &gt;= #{fromTime}</if>
             <if test='toTime != null'> AND e.created_at &lt; #{toTime}</if>
            </script>
            """)
    long countEvents(@Param("tenantId") long tenantId,
                     @Param("eventType") String eventType,
                     @Param("fromTime") LocalDateTime fromTime,
                     @Param("toTime") LocalDateTime toTime);

    /** Events accepted in the window; {@code sinceHours} null means "all time". */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM event e
             WHERE 1 = 1
             <if test='tenantId != null'> AND e.tenant_id = #{tenantId}</if>
             <if test='sinceHours != null'>
               AND e.created_at &gt;= NOW(3) - INTERVAL #{sinceHours} HOUR
             </if>
            </script>
            """)
    long countEventsInWindow(@Param("tenantId") Long tenantId,
                             @Param("sinceHours") Integer sinceHours);

    /** Delivery counts per state, for the success-rate and backlog panels. */
    @Select("""
            <script>
            SELECT d.status AS status, COUNT(*) AS total
              FROM delivery d
             WHERE 1 = 1
             <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
             <if test='sinceHours != null'>
               AND d.created_at &gt;= NOW(3) - INTERVAL #{sinceHours} HOUR
             </if>
             GROUP BY d.status
            </script>
            """)
    List<StatusCount> deliveryStatusCounts(@Param("tenantId") Long tenantId,
                                           @Param("sinceHours") Integer sinceHours);

    /** Undelivered work: what the console calls "积压". */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM delivery d
             WHERE d.status IN ('PENDING', 'DELIVERING', 'RETRY_WAIT')
             <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
            </script>
            """)
    long countInFlight(@Param("tenantId") Long tenantId);

    /**
     * The most common ways deliveries ended up dead.
     *
     * <p>Grouped by (error class, status code) rather than by message: customer
     * response bodies are attacker-controlled text, and grouping on them would both
     * be unbounded and leak payload fragments into an aggregate.
     */
    @Select("""
            <script>
            SELECT d.last_error_class AS errorClass,
                   d.last_status_code AS statusCode,
                   COUNT(*)           AS total
              FROM delivery d
             WHERE d.status = 'DEAD'
             <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
             <if test='sinceHours != null'>
               AND d.created_at &gt;= NOW(3) - INTERVAL #{sinceHours} HOUR
             </if>
             GROUP BY d.last_error_class, d.last_status_code
             ORDER BY total DESC
             LIMIT #{limit}
            </script>
            """)
    List<FailureReasonRow> topFailureReasons(@Param("tenantId") Long tenantId,
                                             @Param("sinceHours") Integer sinceHours,
                                             @Param("limit") int limit);

    /**
     * End-to-end latency of settled deliveries, in microseconds.
     *
     * <p>{@code created_at -> updated_at} is ingest-accepted to attempt-settled; it
     * includes queue wait and every retry, which is what a customer experiences.
     * Per-attempt HTTP time is in {@code delivery_attempt.duration_ms} instead.
     *
     * <p>Returns {@code null} when nothing matched — {@code AVG} over an empty set is
     * {@code NULL}, and turning that into {@code 0} would draw a 0 ms latency on a
     * dashboard that has no data yet.
     */
    @Select("""
            <script>
            SELECT AVG(TIMESTAMPDIFF(MICROSECOND, d.created_at, d.updated_at))
              FROM delivery d
             WHERE d.status = 'SUCCEEDED'
             <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
             <if test='sinceHours != null'>
               AND d.created_at &gt;= NOW(3) - INTERVAL #{sinceHours} HOUR
             </if>
            </script>
            """)
    Double avgSettledLatencyMicros(@Param("tenantId") Long tenantId,
                                   @Param("sinceHours") Integer sinceHours);

    /**
     * The endpoint count excludes soft-deleted rows: the console's tenant table is a count of
     * live callback targets, not of rows that ever existed.
     *
     * <p>Written {@code != 'DELETED'} rather than {@code <> 'DELETED'} because the annotation
     * below is an XML {@code <script>}: {@code <>} would be parsed as an empty element and
     * the mapper would fail to build. The plain (non-script) queries in this file can use
     * {@code <>}.
     */
    @Select("""
            <script>
            SELECT t.id            AS tenantId,
                   t.name          AS name,
                   t.status        AS status,
                   t.rate_limit    AS rateLimit,
                   t.max_in_flight AS maxInFlight,
                   t.weight        AS weight,
                   t.created_at    AS createdAt,
                   (SELECT COUNT(*) FROM endpoint ep
                     WHERE ep.tenant_id = t.id AND ep.status != 'DELETED')      AS endpointCount,
                   (SELECT COUNT(*) FROM api_key k
                     WHERE k.tenant_id = t.id AND k.status = 'ACTIVE')         AS activeKeyCount
              FROM tenant t
             WHERE 1 = 1
             <if test='search != null'> AND t.name LIKE CONCAT('%', #{search}, '%')</if>
             ORDER BY t.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TenantRow> listTenants(@Param("search") String search,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM tenant t
             WHERE 1 = 1
             <if test='search != null'> AND t.name LIKE CONCAT('%', #{search}, '%')</if>
            </script>
            """)
    long countTenants(@Param("search") String search);

    @Select("""
            SELECT t.id            AS tenantId,
                   t.name          AS name,
                   t.status        AS status,
                   t.rate_limit    AS rateLimit,
                   t.max_in_flight AS maxInFlight,
                   t.weight        AS weight,
                   t.created_at    AS createdAt,
                   (SELECT COUNT(*) FROM endpoint ep
                     WHERE ep.tenant_id = t.id AND ep.status != 'DELETED')      AS endpointCount,
                   (SELECT COUNT(*) FROM api_key k
                     WHERE k.tenant_id = t.id AND k.status = 'ACTIVE')         AS activeKeyCount
              FROM tenant t
             WHERE t.id = #{tenantId}
            """)
    TenantRow findTenant(@Param("tenantId") long tenantId);

    @Select("SELECT COUNT(*) FROM tenant WHERE status = 'ACTIVE'")
    long countActiveTenants();

    @Select("SELECT COUNT(*) FROM endpoint WHERE status = 'ACTIVE'")
    long countActiveEndpoints();

    // ------------------------------------------------------------- timeseries

    /**
     * The aligned x-axis for a chart, computed by MySQL.
     *
     * <p>Both ends are floored onto the bucket grid with {@code UNIX_TIMESTAMP}, so
     * {@code fromAt + n * bucketSeconds} is exactly a bucket start and every bucket is
     * keyed by the same value on both sides of a join. Doing this arithmetic in Java
     * would need the database's session timezone, which the application does not have.
     *
     * <p>{@code toAt} is exclusive and is the end of the bucket <b>currently being
     * filled</b>, so the grid has exactly {@code buckets} points for any range: the
     * newest point is partial (that is what a live chart wants), and there is never a
     * trailing bucket with no data in it.
     */
    record TimeWindow(LocalDateTime fromAt, LocalDateTime toAt) {
    }

    @Select("""
            SELECT FROM_UNIXTIME(
                     (FLOOR(UNIX_TIMESTAMP(NOW(3)) / #{bucketSeconds}) + 1 - #{buckets})
                     * #{bucketSeconds})                                        AS fromAt,
                   FROM_UNIXTIME(
                     (FLOOR(UNIX_TIMESTAMP(NOW(3)) / #{bucketSeconds}) + 1)
                     * #{bucketSeconds})                                        AS toAt
            """)
    TimeWindow timeseriesWindow(@Param("bucketSeconds") int bucketSeconds,
                                @Param("buckets") int buckets);

    record BucketCount(LocalDateTime bucketStart, long total) {
    }

    /**
     * One row per bucket, <b>including buckets with no data</b>.
     *
     * <p>The zero-fill is part of the contract, not a convenience: a chart that simply
     * omits quiet buckets draws a line straight from 10:00 to 14:00 and hides a
     * four-hour outage. The recursive CTE materialises the grid (at most a few hundred
     * rows) and is joined after aggregation, so the fact table is still scanned once
     * for the whole window rather than once per bucket.
     */
    @Select("""
            <script>
            WITH RECURSIVE grid AS (
                SELECT #{fromAt} AS bucketStart
                UNION ALL
                SELECT bucketStart + INTERVAL #{bucketSeconds} SECOND
                  FROM grid
                 WHERE bucketStart + INTERVAL #{bucketSeconds} SECOND &lt; #{toAt}
            ),
            agg AS (
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(e.created_at) / #{bucketSeconds})
                       * #{bucketSeconds}) AS bucketStart,
                       COUNT(*) AS total
                  FROM event e
                 WHERE e.created_at &gt;= #{fromAt}
                   AND e.created_at &lt; #{toAt}
                 <if test='tenantId != null'> AND e.tenant_id = #{tenantId}</if>
                 GROUP BY bucketStart
            )
            SELECT g.bucketStart AS bucketStart,
                   COALESCE(a.total, 0) AS total
              FROM grid g
              LEFT JOIN agg a ON a.bucketStart = g.bucketStart
             ORDER BY g.bucketStart
            </script>
            """)
    List<BucketCount> eventBuckets(@Param("tenantId") Long tenantId,
                                   @Param("fromAt") LocalDateTime fromAt,
                                   @Param("toAt") LocalDateTime toAt,
                                   @Param("bucketSeconds") int bucketSeconds);

    /** Attempts <b>started</b> per bucket: the first try and every retry alike. */
    @Select("""
            <script>
            WITH RECURSIVE grid AS (
                SELECT #{fromAt} AS bucketStart
                UNION ALL
                SELECT bucketStart + INTERVAL #{bucketSeconds} SECOND
                  FROM grid
                 WHERE bucketStart + INTERVAL #{bucketSeconds} SECOND &lt; #{toAt}
            ),
            agg AS (
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(a.started_at) / #{bucketSeconds})
                       * #{bucketSeconds}) AS bucketStart,
                       COUNT(*) AS total
                  FROM delivery_attempt a
                  JOIN delivery d ON d.id = a.delivery_id
                 WHERE a.started_at &gt;= #{fromAt}
                   AND a.started_at &lt; #{toAt}
                 <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
                 GROUP BY bucketStart
            )
            SELECT g.bucketStart AS bucketStart,
                   COALESCE(a.total, 0) AS total
              FROM grid g
              LEFT JOIN agg a ON a.bucketStart = g.bucketStart
             ORDER BY g.bucketStart
            </script>
            """)
    List<BucketCount> attemptBuckets(@Param("tenantId") Long tenantId,
                                     @Param("fromAt") LocalDateTime fromAt,
                                     @Param("toAt") LocalDateTime toAt,
                                     @Param("bucketSeconds") int bucketSeconds);

    record SettlementBucket(LocalDateTime bucketStart, long succeeded, long dead, long canceled) {
    }

    /**
     * Deliveries that reached a terminal state in each bucket.
     *
     * <p>{@code updated_at} is the settle time for a terminal row: the
     * {@code ON UPDATE CURRENT_TIMESTAMP} column stops changing once a delivery stops
     * changing, so it is the timestamp this chart needs without an extra column and an
     * extra migration.
     *
     * <p>Bucketed by settle time, not creation time, so the line shows work <i>finishing</i>
     * — a burst that takes an hour to drain stays visibly late instead of looking
     * instantly healthy at ingest.
     */
    @Select("""
            <script>
            WITH RECURSIVE grid AS (
                SELECT #{fromAt} AS bucketStart
                UNION ALL
                SELECT bucketStart + INTERVAL #{bucketSeconds} SECOND
                  FROM grid
                 WHERE bucketStart + INTERVAL #{bucketSeconds} SECOND &lt; #{toAt}
            ),
            agg AS (
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(d.updated_at) / #{bucketSeconds})
                       * #{bucketSeconds}) AS bucketStart,
                       SUM(d.status = 'SUCCEEDED') AS succeeded,
                       SUM(d.status = 'DEAD')      AS dead,
                       SUM(d.status = 'CANCELED')  AS canceled
                  FROM delivery d
                 WHERE d.updated_at &gt;= #{fromAt}
                   AND d.updated_at &lt; #{toAt}
                   AND d.status IN ('SUCCEEDED', 'DEAD', 'CANCELED')
                 <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
                 GROUP BY bucketStart
            )
            SELECT g.bucketStart AS bucketStart,
                   COALESCE(a.succeeded, 0) AS succeeded,
                   COALESCE(a.dead, 0)      AS dead,
                   COALESCE(a.canceled, 0)  AS canceled
              FROM grid g
              LEFT JOIN agg a ON a.bucketStart = g.bucketStart
             ORDER BY g.bucketStart
            </script>
            """)
    List<SettlementBucket> settlementBuckets(@Param("tenantId") Long tenantId,
                                             @Param("fromAt") LocalDateTime fromAt,
                                             @Param("toAt") LocalDateTime toAt,
                                             @Param("bucketSeconds") int bucketSeconds);

    // ---------------------------------------------------------------- latency

    record LatencyStats(Long p50Ms, Long p95Ms, Long p99Ms, long samples) {
    }

    /**
     * Nearest-rank percentiles of per-attempt HTTP duration, in one pass.
     *
     * <p>{@code CUME_DIST} avoids the {@code COUNT} then {@code LIMIT 1 OFFSET} dance
     * (which needs a second round trip and can disagree with the first if a row lands
     * in between). "Nearest rank" means the reported p95 is an actual observed
     * duration, not an interpolation — for an SLA conversation that is the more
     * defensible number.
     *
     * <p>This is what makes a latency panel possible at all today: no Micrometer
     * timer is registered, so the only latency evidence in the system is
     * {@code delivery_attempt.duration_ms}.
     */
    @Select("""
            <script>
            SELECT COUNT(*) AS samples,
                   MIN(CASE WHEN cd &gt;= 0.50 THEN durationMs END) AS p50Ms,
                   MIN(CASE WHEN cd &gt;= 0.95 THEN durationMs END) AS p95Ms,
                   MIN(CASE WHEN cd &gt;= 0.99 THEN durationMs END) AS p99Ms
              FROM (
                    SELECT a.duration_ms AS durationMs,
                           CUME_DIST() OVER (ORDER BY a.duration_ms) AS cd
                      FROM delivery_attempt a
                      JOIN delivery d ON d.id = a.delivery_id
                     WHERE a.duration_ms IS NOT NULL
                       AND a.started_at &gt;= NOW(3) - INTERVAL #{hours} HOUR
                     <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
                   ) ranked
            </script>
            """)
    LatencyStats latencyStats(@Param("tenantId") Long tenantId,
                              @Param("hours") int hours);

    /** {@code upperBoundMs} is null for the open-ended "slower than the last edge" bucket. */
    record LatencyBucketRow(Integer upperBoundMs, long total) {
    }

    /**
     * Fixed-edge histogram of attempt durations.
     *
     * <p>Edges are pinned in SQL rather than configured: they are the contract the
     * frontend bucket labels are built from, and a chart whose x-axis silently changed
     * shape between deployments is worse than no chart.
     */
    @Select("""
            <script>
            SELECT CASE
                     WHEN a.duration_ms &lt; 50    THEN 50
                     WHEN a.duration_ms &lt; 100   THEN 100
                     WHEN a.duration_ms &lt; 250   THEN 250
                     WHEN a.duration_ms &lt; 500   THEN 500
                     WHEN a.duration_ms &lt; 1000  THEN 1000
                     WHEN a.duration_ms &lt; 2500  THEN 2500
                     WHEN a.duration_ms &lt; 5000  THEN 5000
                     WHEN a.duration_ms &lt; 10000 THEN 10000
                     ELSE NULL
                   END AS upperBoundMs,
                   COUNT(*) AS total
              FROM delivery_attempt a
              JOIN delivery d ON d.id = a.delivery_id
             WHERE a.duration_ms IS NOT NULL
               AND a.started_at &gt;= NOW(3) - INTERVAL #{hours} HOUR
             <if test='tenantId != null'> AND d.tenant_id = #{tenantId}</if>
             GROUP BY upperBoundMs
            </script>
            """)
    List<LatencyBucketRow> latencyHistogram(@Param("tenantId") Long tenantId,
                                            @Param("hours") int hours);
}
