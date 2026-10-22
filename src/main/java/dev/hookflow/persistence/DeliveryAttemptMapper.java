package dev.hookflow.persistence;

import dev.hookflow.domain.delivery.DeliveryAttempt;
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
}
