package dev.hookflow.persistence;

import dev.hookflow.domain.outbox.OutboxMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface OutboxMapper {

    /**
     * @param tenantId   who owns the delivery; the scheduler's first dimension
     * @param endpointId where it will be posted; the scheduler's second dimension
     */
    @Insert("""
            INSERT INTO outbox (aggregate_type, aggregate_id, attempt_no, payload,
                                tenant_id, endpoint_id, status)
            VALUES (#{aggregateType}, #{aggregateId}, #{attemptNo}, #{payload},
                    #{tenantId}, #{endpointId}, 'NEW')
            """)
    int insert(@Param("aggregateType") String aggregateType,
               @Param("aggregateId") long aggregateId,
               @Param("attemptNo") int attemptNo,
               @Param("payload") String payload,
               @Param("tenantId") long tenantId,
               @Param("endpointId") long endpointId);

    /**
     * One statement for the whole batch's "publish this attempt" rows.
     *
     * <p>Only used for brand-new deliveries, where a duplicate key is impossible: the
     * row is keyed by {@code (aggregate_type, delivery_id, attempt_no)} and both the
     * delivery and the attempt are created in this same transaction. Re-arming an
     * existing row is the job of {@code OutboxWriter.append}, not of this statement.
     *
     * <p>Rows are maps: {@code deliveryId}, {@code attemptNo}, {@code payload},
     * {@code tenantId}, {@code endpointId}.
     */
    @Insert("""
            <script>
            INSERT INTO outbox (aggregate_type, aggregate_id, attempt_no, payload,
                                tenant_id, endpoint_id, status)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                ('DELIVERY', #{row.deliveryId}, #{row.attemptNo}, #{row.payload},
                 #{row.tenantId}, #{row.endpointId}, 'NEW')
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<Map<String, Object>> rows);

    /**
     * Locks the next batch for exactly one relay instance, skipping the tenants
     * and endpoints that are already at their concurrency budget.
     *
     * <p>Two things happen in this one statement, and both are required:
     *
     * <ul>
     *   <li>{@code SKIP LOCKED} lets a second relay step over rows that are
     *       already locked instead of blocking or re-publishing them.</li>
     *   <li>{@code NOT IN (blocked)} is what makes the backlog schedulable at
     *       all. Without it the first {@code limit} rows belong to whichever
     *       tenant is flooding, every one of them is refused by the admission
     *       check, and the small tenant's rows at the end of the queue are never
     *       even looked at — the exact starvation v0.3 sets out to remove.</li>
     * </ul>
     *
     * <p>The blocked lists are the relay's last known view and may be stale by
     * one refresh interval; that is fine, because the caller re-checks every
     * selected row against {@code AdmissionControl} before publishing it.
     */
    @Select("""
            <script>
            SELECT * FROM outbox
             WHERE status = 'NEW'
            <if test="blockedTenants != null and !blockedTenants.isEmpty()">
               AND tenant_id NOT IN
               <foreach collection="blockedTenants" item="tenantId" open="(" separator="," close=")">#{tenantId}</foreach>
            </if>
            <if test="blockedEndpoints != null and !blockedEndpoints.isEmpty()">
               AND endpoint_id NOT IN
               <foreach collection="blockedEndpoints" item="endpointId" open="(" separator="," close=")">#{endpointId}</foreach>
            </if>
             ORDER BY id
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            </script>
            """)
    List<OutboxMessage> lockSchedulableBatch(@Param("limit") int limit,
                                             @Param("blockedTenants") List<Long> blockedTenants,
                                             @Param("blockedEndpoints") List<Long> blockedEndpoints);

    @Update("<script>"
            + "UPDATE outbox SET status = 'PUBLISHING', publishing_since = NOW(3),"
            + " publish_attempts = publish_attempts + 1"
            + " WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markPublishing(@Param("ids") List<Long> ids);

    /**
     * Marks a whole confirmed batch PUBLISHED in one statement.
     *
     * <p>v0.4: this used to be one UPDATE per row, i.e. one commit (one fsync) per
     * published event — measured at ~12 ms, about half of the relay's per-row budget.
     * The bulk form costs one commit per batch. Only rows whose publish was actually
     * acknowledged may be passed here; the caller owns that rule.
     */
    @Update("<script>"
            + "UPDATE outbox SET status = 'PUBLISHED', published_at = NOW(3)"
            + " WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markPublishedBatch(@Param("ids") List<Long> ids);

    /**
     * Puts a batch of failed publishes back in the queue, in one statement.
     *
     * <p>Same reasoning as {@link #markPublishedBatch}: a broker outage used to cost
     * one commit per parked row per tick. The {@code status = 'PUBLISHING'} guard is
     * what keeps this from resurrecting a row another process already handled.
     */
    @Update("<script>"
            + "UPDATE outbox SET status = 'NEW', publishing_since = NULL"
            + " WHERE status = 'PUBLISHING' AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int releaseBackBatch(@Param("ids") List<Long> ids);

    /**
     * Recovery for the crash window "broker confirmed, row not yet marked".
     * <p>Rows stuck in PUBLISHING are re-published, which is precisely why the
     * end-to-end guarantee is at-least-once and not exactly-once.
     */
    @Update("""
            UPDATE outbox
               SET status = 'NEW', publishing_since = NULL
             WHERE status = 'PUBLISHING'
               AND publishing_since < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
             LIMIT #{limit}
            """)
    int recoverStalePublishing(@Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * Re-arms a row that the broker accepted but that produced no effect.
     *
     * <p>{@code PUBLISHED} only means "the broker took it", not "a worker completed
     * the attempt". If the message was lost after the confirm, or the consumer
     * nacked it, the intent is still true but the row is already PUBLISHED, so a
     * plain INSERT of the same attempt would be rejected by the uniqueness key and
     * the delivery would sit in RETRY_WAIT forever. Re-arming after a grace window
     * closes that hole without weakening the uniqueness guarantee.
     */
    @Update("""
            UPDATE outbox
               SET status = 'NEW', publishing_since = NULL, published_at = NULL
             WHERE aggregate_type = #{aggregateType}
               AND aggregate_id = #{aggregateId}
               AND attempt_no = #{attemptNo}
               AND status = 'PUBLISHED'
               AND published_at < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
            """)
    int rearmStalePublished(@Param("aggregateType") String aggregateType,
                            @Param("aggregateId") long aggregateId,
                            @Param("attemptNo") int attemptNo,
                            @Param("staleSeconds") int staleSeconds);

    @Select("SELECT COUNT(*) FROM outbox WHERE status = 'NEW'")
    long countPending();

    /**
     * Work that the broker has not accepted yet.
     *
     * <p>Distinct from {@link #countPending()} on purpose: a row cycles between NEW
     * and PUBLISHING every relay tick while the broker is down, so NEW alone reads as
     * zero exactly when the backlog is worst. This is the number an operator should
     * alert on.
     */
    @Select("SELECT COUNT(*) FROM outbox WHERE status IN ('NEW', 'PUBLISHING')")
    long countUnpublished();

    /**
     * One tenant's share of the un-published backlog.
     *
     * <p>This is the number load shedding has to look at, not the event count: a
     * tenant whose 10 000 events are already delivered has no backlog at all, while
     * a tenant with 100 undeliverable events has a real one.
     */
    @Select("""
            SELECT COUNT(*) FROM outbox
             WHERE tenant_id = #{tenantId} AND status IN ('NEW', 'PUBLISHING')
            """)
    long countUnpublishedByTenant(@Param("tenantId") long tenantId);

    /** Every tenant's un-published backlog in one round trip, for the shedding gate. */
    @Select("""
            SELECT tenant_id AS id, COUNT(*) AS in_flight
              FROM outbox
             WHERE status IN ('NEW', 'PUBLISHING')
             GROUP BY tenant_id
            """)
    List<dev.hookflow.domain.fairness.InFlight> countUnpublishedByTenants();

    /**
     * Prunes rows that have been published longer than the retention window.
     *
     * <p>Without this the table grows without bound: one row per delivery attempt,
     * forever. Kept as a bounded batch so a large backlog cannot turn into one
     * multi-minute DELETE that blocks the relay.
     */
    @Delete("""
            DELETE FROM outbox
             WHERE status = 'PUBLISHED'
               AND published_at < DATE_SUB(NOW(3), INTERVAL #{retentionDays} DAY)
             LIMIT #{batchSize}
            """)
    int deletePublishedBefore(@Param("retentionDays") int retentionDays, @Param("batchSize") int batchSize);
}
