package dev.hookflow.demo;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read (and reset) queries used by the demo console only.
 *
 * <p>Kept out of {@code DeliveryMapper} on purpose: the delivery mappers are the
 * state machine, where every statement is a compare-and-set that a test can rely on.
 * These are reporting queries with a {@code LIMIT}, and mixing them in would blur
 * that line.
 */
@Mapper
public interface DemoMapper {

    record StatusCount(Long tenantId, String status, long total) {
    }

    record DeliveryRow(Long id,
                       Long tenantId,
                       Long eventId,
                       String eventType,
                       String status,
                       Integer attemptCount,
                       Integer maxAttempts,
                       String lastErrorClass,
                       Integer lastStatusCode,
                       LocalDateTime updatedAt,
                       Long latencyMicros) {
    }

    @Select("""
            <script>
            SELECT tenant_id AS tenantId, status AS status, COUNT(*) AS total
              FROM delivery
             WHERE tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY tenant_id, status
            </script>
            """)
    List<StatusCount> statusCounts(@Param("tenantIds") List<Long> tenantIds);

    /**
     * End-to-end latency samples (ingest accepted -> delivery settled).
     *
     * <p>Bounded by {@code limit}: percentiles from the last few hundred deliveries
     * are what the console needs, and sorting 100 000 rows to draw a sparkline would
     * make the console itself the load.
     */
    @Select("""
            SELECT TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) AS micros
              FROM delivery
             WHERE tenant_id = #{tenantId} AND status = 'SUCCEEDED'
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Long> recentSucceededMicros(@Param("tenantId") long tenantId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT d.id, d.tenant_id AS tenantId, d.event_id AS eventId,
                   e.event_type AS eventType, d.status AS status,
                   d.attempt_count AS attemptCount, d.max_attempts AS maxAttempts,
                   d.last_error_class AS lastErrorClass, d.last_status_code AS lastStatusCode,
                   d.updated_at AS updatedAt,
                   TIMESTAMPDIFF(MICROSECOND, d.created_at, d.updated_at) AS latencyMicros
              FROM delivery d
              JOIN event e ON e.id = d.event_id
             WHERE d.tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY d.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<DeliveryRow> recentDeliveries(@Param("tenantIds") List<Long> tenantIds,
                                       @Param("limit") int limit);

    // ------------------------------------------------------------------ reset

    @Delete("""
            <script>
            DELETE da FROM delivery_attempt da
              JOIN delivery d ON d.id = da.delivery_id
             WHERE d.tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteAttempts(@Param("tenantIds") List<Long> tenantIds);

    @Delete("""
            <script>
            DELETE FROM outbox
             WHERE tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteOutbox(@Param("tenantIds") List<Long> tenantIds);

    @Delete("""
            <script>
            DELETE FROM delivery
             WHERE tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteDeliveries(@Param("tenantIds") List<Long> tenantIds);

    @Delete("""
            <script>
            DELETE FROM event
             WHERE tenant_id IN
             <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteEvents(@Param("tenantIds") List<Long> tenantIds);
}
