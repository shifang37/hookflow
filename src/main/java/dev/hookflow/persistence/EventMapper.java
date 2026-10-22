package dev.hookflow.persistence;

import dev.hookflow.domain.event.Event;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * The idempotency read path. Also used to recover the generated id after an
     * insert, which keeps the mapper usable with immutable record types.
     */
    @Select("SELECT * FROM event WHERE tenant_id = #{tenantId} AND idempotency_key = #{key}")
    Optional<Event> findByTenantAndIdempotencyKey(@Param("tenantId") long tenantId,
                                                  @Param("key") String key);

    @Select("SELECT * FROM event WHERE id = #{id} AND tenant_id = #{tenantId}")
    Optional<Event> findByIdAndTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    /** Unscoped lookup for the delivery worker, which already holds a trusted delivery id. */
    @Select("SELECT * FROM event WHERE id = #{id}")
    Optional<Event> findById(@Param("id") long id);
}
