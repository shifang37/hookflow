package dev.hookflow.persistence;

import dev.hookflow.domain.event.Event;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface EventMapper {

    @Insert("""
            INSERT INTO event (tenant_id, event_type, payload, idempotency_key)
            VALUES (#{tenantId}, #{eventType}, #{payload}, #{idempotencyKey})
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("eventType") String eventType,
               @Param("payload") String payload,
               @Param("idempotencyKey") String idempotencyKey);

    /**
     * One statement for N events.
     *
     * <p>Each row is a map rather than a record because MyBatis resolves
     * {@code #{row.eventType}} through a getter, and this project's immutable records
     * expose record accessors. Maps keep the nested property access working without
     * a mutable DTO.
     *
     * <p>Keys: {@code eventType}, {@code payload}, {@code idempotencyKey}. The
     * generated ids are not returned by MySQL for a multi-row insert here, so the
     * caller reads the rows back by idempotency key inside the same transaction.
     */
    @Insert("""
            <script>
            INSERT INTO event (tenant_id, event_type, payload, idempotency_key) VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{tenantId}, #{row.eventType}, #{row.payload}, #{row.idempotencyKey})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("tenantId") long tenantId, @Param("rows") List<Map<String, Object>> rows);

    /**
     * The idempotency read path. Also used to recover the generated id after an
     * insert, which keeps the mapper usable with immutable record types.
     */
    @Select("SELECT * FROM event WHERE tenant_id = #{tenantId} AND idempotency_key = #{key}")
    Optional<Event> findByTenantAndIdempotencyKey(@Param("tenantId") long tenantId,
                                                  @Param("key") String key);

    /**
     * Classifies a whole batch against what already exists: one round trip instead
     * of one SELECT per item. Used both before inserting (to find replays) and after
     * (to read back the generated ids).
     */
    @Select("""
            <script>
            SELECT * FROM event
             WHERE tenant_id = #{tenantId}
               AND idempotency_key IN
               <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
            </script>
            """)
    List<Event> findByTenantAndIdempotencyKeys(@Param("tenantId") long tenantId,
                                               @Param("keys") Collection<String> keys);

    @Select("SELECT * FROM event WHERE id = #{id} AND tenant_id = #{tenantId}")
    Optional<Event> findByIdAndTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    /** Unscoped lookup for the delivery worker, which already holds a trusted delivery id. */
    @Select("SELECT * FROM event WHERE id = #{id}")
    Optional<Event> findById(@Param("id") long id);
}
