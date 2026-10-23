package dev.hookflow.persistence;

import dev.hookflow.domain.delivery.DeliveryAttempt;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DeliveryAttemptMapper {

    @Insert("""
            INSERT INTO delivery_attempt (delivery_id, attempt_no, status)
            VALUES (#{deliveryId}, #{attemptNo}, 'IN_PROGRESS')
            """)
    int insertInProgress(@Param("deliveryId") long deliveryId, @Param("attemptNo") int attemptNo);

    @Update("""
            UPDATE delivery_attempt
               SET status = #{status},
                   status_code = #{statusCode},
                   response_preview = #{responsePreview},
                   error_class = #{errorClass},
                   duration_ms = #{durationMs},
                   finished_at = NOW(3)
             WHERE delivery_id = #{deliveryId}
               AND attempt_no = #{attemptNo}
               AND status = 'IN_PROGRESS'
            """)
    int finish(@Param("deliveryId") long deliveryId,
               @Param("attemptNo") int attemptNo,
               @Param("status") String status,
               @Param("statusCode") Integer statusCode,
               @Param("responsePreview") String responsePreview,
               @Param("errorClass") String errorClass,
               @Param("durationMs") Integer durationMs);

    @Select("SELECT * FROM delivery_attempt WHERE delivery_id = #{deliveryId} ORDER BY attempt_no")
    List<DeliveryAttempt> listByDelivery(@Param("deliveryId") long deliveryId);

    @Update("""
            UPDATE delivery_attempt
               SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED', finished_at = NOW(3)
             WHERE delivery_id = #{deliveryId} AND attempt_no = #{attemptNo}
               AND status = 'IN_PROGRESS'
            """)
    int abandon(@Param("deliveryId") long deliveryId, @Param("attemptNo") int attemptNo);

    /**
     * Prunes attempt history that is older than the retention window.
     *
     * <p><b>{@code IN_PROGRESS} rows are never touched.</b> {@link #finish} has
     * {@code status = 'IN_PROGRESS'} in its WHERE clause, so deleting the row of a
     * live attempt would make the settle update affect zero rows and the attempt
     * outcome would be lost without any error. The lease TTL bounds how long a row
     * can legitimately stay in that state, and the lease sweeper flips the rows of
     * dead workers to {@code ABANDONED}, after which they age out normally.
     *
     * <p>Bounded by {@code batchSize} on purpose: an unbounded DELETE over months of
     * history would be one long lock hold, and the sweeper can simply run again.
     */
    @Delete("""
            DELETE FROM delivery_attempt
             WHERE status <> 'IN_PROGRESS'
               AND started_at < DATE_SUB(NOW(3), INTERVAL #{retentionDays} DAY)
             LIMIT #{batchSize}
            """)
    int deleteFinishedBefore(@Param("retentionDays") int retentionDays,
                             @Param("batchSize") int batchSize);
}
