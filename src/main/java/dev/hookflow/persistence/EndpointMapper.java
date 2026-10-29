package dev.hookflow.persistence;

import dev.hookflow.domain.endpoint.Endpoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface EndpointMapper {

    @Insert("""
            INSERT INTO endpoint (tenant_id, url, secret_cipher, secret_key_version,
                                  subscribed_types, max_attempts, max_concurrency, status)
            VALUES (#{tenantId}, #{url}, #{secretCipher}, #{secretKeyVersion},
                    #{subscribedTypes}, #{maxAttempts}, #{maxConcurrency}, 'ACTIVE')
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("url") String url,
               @Param("secretCipher") byte[] secretCipher,
               @Param("secretKeyVersion") short secretKeyVersion,
               @Param("subscribedTypes") String subscribedTypes,
               @Param("maxAttempts") int maxAttempts,
               @Param("maxConcurrency") int maxConcurrency);

    /** Only endpoints that declare a cap; the empty result means "nobody caps". */
    @Select("""
            SELECT id, max_concurrency
              FROM endpoint
             WHERE status = 'ACTIVE' AND max_concurrency > 0
            """)
    List<dev.hookflow.domain.fairness.EndpointCap> listConcurrencyCaps();

    /**
     * Every reader below excludes {@code DELETED}. That predicate is not decoration: a
     * soft-deleted row still has its URL and (via the generated {@code url_key}) no longer
     * holds the unique key, so a lookup that forgot the filter could hand back the tombstone
     * instead of the endpoint the caller just created.
     */
    @Select("""
            SELECT * FROM endpoint
             WHERE id = #{id} AND tenant_id = #{tenantId} AND status <> 'DELETED'
            """)
    Optional<Endpoint> findByIdAndTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    @Select("""
            SELECT * FROM endpoint
             WHERE tenant_id = #{tenantId} AND url = #{url} AND status <> 'DELETED'
            """)
    Optional<Endpoint> findByTenantAndUrl(@Param("tenantId") long tenantId, @Param("url") String url);

    /** Unscoped lookup for the delivery worker, which already holds a trusted endpoint id. */
    @Select("SELECT * FROM endpoint WHERE id = #{id}")
    Optional<Endpoint> findById(@Param("id") long id);

    @Select("""
            SELECT * FROM endpoint
             WHERE tenant_id = #{tenantId} AND status <> 'DELETED'
             ORDER BY id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Endpoint> listByTenant(@Param("tenantId") long tenantId,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM endpoint WHERE tenant_id = #{tenantId} AND status <> 'DELETED'")
    long countByTenant(@Param("tenantId") long tenantId);

    /** All active endpoints of a tenant; the fan-out step filters by event type in memory. */
    @Select("SELECT * FROM endpoint WHERE tenant_id = #{tenantId} AND status = 'ACTIVE'")
    List<Endpoint> listActiveByTenant(@Param("tenantId") long tenantId);

    @Update("""
            UPDATE endpoint SET status = #{status}
             WHERE id = #{id} AND tenant_id = #{tenantId} AND status <> 'DELETED'
            """)
    int updateStatus(@Param("id") long id,
                     @Param("tenantId") long tenantId,
                     @Param("status") String status);

    /**
     * The soft-delete itself: a one-way transition guarded so a concurrent delete (or a
     * pause racing a delete) cannot resurrect the row.
     */
    @Update("""
            UPDATE endpoint SET status = 'DELETED'
             WHERE id = #{id} AND tenant_id = #{tenantId} AND status <> 'DELETED'
            """)
    int markDeleted(@Param("id") long id, @Param("tenantId") long tenantId);
}
