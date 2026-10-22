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
                                  subscribed_types, max_attempts, status)
            VALUES (#{tenantId}, #{url}, #{secretCipher}, #{secretKeyVersion},
                    #{subscribedTypes}, #{maxAttempts}, 'ACTIVE')
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("url") String url,
               @Param("secretCipher") byte[] secretCipher,
               @Param("secretKeyVersion") short secretKeyVersion,
               @Param("subscribedTypes") String subscribedTypes,
               @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM endpoint WHERE id = #{id} AND tenant_id = #{tenantId}")
    Optional<Endpoint> findByIdAndTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    @Select("SELECT * FROM endpoint WHERE tenant_id = #{tenantId} AND url = #{url}")
    Optional<Endpoint> findByTenantAndUrl(@Param("tenantId") long tenantId, @Param("url") String url);

    /** Unscoped lookup for the delivery worker, which already holds a trusted endpoint id. */
    @Select("SELECT * FROM endpoint WHERE id = #{id}")
    Optional<Endpoint> findById(@Param("id") long id);

    @Select("""
            SELECT * FROM endpoint
             WHERE tenant_id = #{tenantId}
             ORDER BY id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Endpoint> listByTenant(@Param("tenantId") long tenantId,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM endpoint WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") long tenantId);

    /** All active endpoints of a tenant; the fan-out step filters by event type in memory. */
    @Select("SELECT * FROM endpoint WHERE tenant_id = #{tenantId} AND status = 'ACTIVE'")
    List<Endpoint> listActiveByTenant(@Param("tenantId") long tenantId);

    @Update("UPDATE endpoint SET status = #{status} WHERE id = #{id} AND tenant_id = #{tenantId}")
    int updateStatus(@Param("id") long id,
                     @Param("tenantId") long tenantId,
                     @Param("status") String status);
}
