package dev.hookflow.persistence;

import dev.hookflow.domain.delivery.Delivery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * Every state transition is a compare-and-set: the WHERE clause carries the state
 * we believed the row was in. A zero row-count therefore means "somebody else
 * moved it first" and must never be treated as success.
 *
 * <p><b>All time arithmetic happens inside MySQL.</b> Two clocks must never be
 * compared: an application clock (JVM timezone) and a database clock (server
 * timezone) can disagree by hours, and the only symptom is "retries never fire" —
 * see {@code docs/failures/0002-two-clocks.md}. Deadlines are therefore always
 * expressed as {@code NOW(3) + INTERVAL n SECOND} and durations, never absolute
 * timestamps computed in Java.
 */
@Mapper
public interface DeliveryMapper {

    @Insert("""
            INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts)
            VALUES (#{tenantId}, #{eventId}, #{endpointId}, 'PENDING', 0, #{maxAttempts})
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("eventId") long eventId,
               @Param("endpointId") long endpointId,
               @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM delivery WHERE id = #{id} AND tenant_id = #{tenantId}")
    Optional<Delivery> findByIdAndTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    @Select("SELECT * FROM delivery WHERE id = #{id}")
    Optional<Delivery> findById(@Param("id") long id);

    @Select("SELECT * FROM delivery WHERE event_id = #{eventId} AND endpoint_id = #{endpointId}")
    Optional<Delivery> findByEventAndEndpoint(@Param("eventId") long eventId,
                                              @Param("endpointId") long endpointId);

    @Select("<script>"
            + "SELECT * FROM delivery WHERE tenant_id = #{tenantId}"
            + "<if test='eventId != null'> AND event_id = #{eventId}</if>"
            + "<if test='status != null'> AND status = #{status}</if>"
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<Delivery> list(@Param("tenantId") long tenantId,
                        @Param("eventId") Long eventId,
                        @Param("status") String status,
                        @Param("limit") int limit,
                        @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM delivery WHERE tenant_id = #{tenantId}"
            + "<if test='eventId != null'> AND event_id = #{eventId}</if>"
            + "<if test='status != null'> AND status = #{status}</if>"
            + "</script>")
    long count(@Param("tenantId") long tenantId,
               @Param("eventId") Long eventId,
               @Param("status") String status);

    /**
     * Claim one delivery for one attempt.
     *
     * <p>{@code attemptCountBefore} is the value the message claims the row holds.
     * A duplicated broker message for an attempt that already happened therefore
     * updates zero rows instead of delivering twice.
     *
     * @param leaseSeconds lease length; negative values produce an already-expired
     *                     lease and exist only so tests can exercise reclaiming
     */
    @Update("""
            UPDATE delivery
               SET status = 'DELIVERING',
                   attempt_count = attempt_count + 1,
                   lease_token = #{leaseToken},
                   lease_until = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND),
                   next_attempt_at = NULL,
                   updated_at = NOW(3)
             WHERE id = #{id}
               AND status IN ('PENDING', 'RETRY_WAIT')
               AND attempt_count = #{attemptCountBefore}
            """)
    int claim(@Param("id") long id,
              @Param("attemptCountBefore") int attemptCountBefore,
              @Param("leaseToken") String leaseToken,
              @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE delivery
               SET status = 'SUCCEEDED',
                   lease_token = NULL,
                   lease_until = NULL,
                   next_attempt_at = NULL,
                   attempt_count = #{attemptCount},
                   last_status_code = #{statusCode},
                   last_error_class = NULL,
                   updated_at = NOW(3)
             WHERE id = #{id} AND status = 'DELIVERING' AND lease_token = #{leaseToken}
            """)
    int markSucceeded(@Param("id") long id,
                      @Param("leaseToken") String leaseToken,
                      @Param("attemptCount") int attemptCount,
                      @Param("statusCode") Integer statusCode);

    @Update("""
            UPDATE delivery
               SET status = 'RETRY_WAIT',
                   lease_token = NULL,
                   lease_until = NULL,
                   attempt_count = #{attemptCount},
                   next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{delayMicros} MICROSECOND),
                   last_status_code = #{statusCode},
                   last_error_class = #{errorClass},
                   updated_at = NOW(3)
             WHERE id = #{id} AND status = 'DELIVERING' AND lease_token = #{leaseToken}
            """)
    int markRetryWait(@Param("id") long id,
                      @Param("leaseToken") String leaseToken,
                      @Param("attemptCount") int attemptCount,
                      @Param("delayMicros") long delayMicros,
                      @Param("statusCode") Integer statusCode,
                      @Param("errorClass") String errorClass);

    @Update("""
            UPDATE delivery
               SET status = 'DEAD',
                   lease_token = NULL,
                   lease_until = NULL,
                   next_attempt_at = NULL,
                   attempt_count = #{attemptCount},
                   last_status_code = #{statusCode},
                   last_error_class = #{errorClass},
                   updated_at = NOW(3)
             WHERE id = #{id} AND status = 'DELIVERING' AND lease_token = #{leaseToken}
            """)
    int markDead(@Param("id") long id,
                 @Param("leaseToken") String leaseToken,
                 @Param("attemptCount") int attemptCount,
                 @Param("statusCode") Integer statusCode,
                 @Param("errorClass") String errorClass);

    @Update("""
            UPDATE delivery
               SET status = 'CANCELED', lease_token = NULL, lease_until = NULL, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'DELIVERING' AND lease_token = #{leaseToken}
            """)
    int markCanceled(@Param("id") long id, @Param("leaseToken") String leaseToken);

    /**
     * Reclaim a single delivery whose lease expired. The returning worker may
     * still be alive; it is fenced off because its lease token no longer matches.
     */
    @Update("""
            UPDATE delivery
               SET status = 'PENDING',
                   lease_token = NULL,
                   lease_until = NULL,
                   updated_at = NOW(3)
             WHERE id = #{id} AND status = 'DELIVERING' AND lease_until < NOW(3)
            """)
    int reclaimExpiredLease(@Param("id") long id);

    @Select("""
            SELECT * FROM delivery
             WHERE status = 'DELIVERING' AND lease_until < NOW(3)
             ORDER BY lease_until
             LIMIT #{limit}
            """)
    List<Delivery> findExpiredLeases(@Param("limit") int limit);

    @Select("""
            SELECT * FROM delivery
             WHERE status = 'RETRY_WAIT' AND next_attempt_at <= NOW(3)
             ORDER BY next_attempt_at
             LIMIT #{limit}
            """)
    List<Delivery> findDueRetries(@Param("limit") int limit);

    /**
     * Safety net for "the broker lost the message". Because MySQL is the source
     * of truth, a delivery stuck in PENDING past the grace window is simply
     * re-enqueued; losing a broker message therefore never loses a delivery.
     */
    @Select("""
            SELECT * FROM delivery
             WHERE status = 'PENDING'
               AND updated_at < DATE_SUB(NOW(3), INTERVAL #{graceSeconds} SECOND)
             ORDER BY id
             LIMIT #{limit}
            """)
    List<Delivery> findStuckPending(@Param("graceSeconds") int graceSeconds, @Param("limit") int limit);

    /** Manual replay: give a dead delivery exactly one more attempt budget. */
    @Update("""
            UPDATE delivery
               SET status = 'PENDING',
                   max_attempts = attempt_count + 1,
                   next_attempt_at = NULL,
                   lease_token = NULL,
                   lease_until = NULL,
                   updated_at = NOW(3)
             WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'DEAD'
            """)
    int replay(@Param("id") long id, @Param("tenantId") long tenantId);

    /** Pausing an endpoint stops its in-flight retries; they are not resumed later. */
    @Update("""
            UPDATE delivery
               SET status = 'CANCELED', next_attempt_at = NULL, updated_at = NOW(3)
             WHERE endpoint_id = #{endpointId} AND tenant_id = #{tenantId}
               AND status IN ('PENDING', 'RETRY_WAIT')
            """)
    int cancelPendingForEndpoint(@Param("endpointId") long endpointId, @Param("tenantId") long tenantId);

    /**
     * In-flight counts, the input of every admission decision.
     *
     * <p>{@code status = 'DELIVERING'} is range-scanned on {@code idx_delivery_due
     * (status, next_attempt_at)} and only ever touches rows that are executing
     * right now — a set bounded by the worker fleet, not by the backlog. That is
     * why counting them on the scheduler's hot path is affordable.
     */
    @Select("""
            SELECT tenant_id AS id, COUNT(*) AS in_flight
              FROM delivery
             WHERE status = 'DELIVERING'
             GROUP BY tenant_id
            """)
    List<dev.hookflow.domain.fairness.InFlight> countInFlightByTenant();

    @Select("""
            SELECT endpoint_id AS id, COUNT(*) AS in_flight
              FROM delivery
             WHERE status = 'DELIVERING'
             GROUP BY endpoint_id
            """)
    List<dev.hookflow.domain.fairness.InFlight> countInFlightByEndpoint();
}
