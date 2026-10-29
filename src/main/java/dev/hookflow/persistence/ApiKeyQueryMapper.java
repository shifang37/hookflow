package dev.hookflow.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Key metadata for the operator console.
 *
 * <p>Separate from {@link ApiKeyMapper} on purpose, and the distinction is the whole
 * point: that mapper reads {@code key_hash} because authentication needs it. This one
 * never selects the column, so a future console endpoint cannot leak a hash by
 * accident — the field is not in the row, not just filtered out of a DTO.
 *
 * <p>Kept out of {@link ReportMapper} too: that one holds aggregates and windowed
 * counts, this one is a plain list of one tenant's rows.
 */
@Mapper
public interface ApiKeyQueryMapper {

    record ApiKeyRow(
            Long id,
            String keyPrefix,
            String scopes,
            String status,
            LocalDateTime createdAt,
            LocalDateTime revokedAt) {
    }

    /** Newest first; revoked keys stay in the list so an operator can see what was rotated away. */
    @Select("""
            SELECT id,
                   key_prefix AS keyPrefix,
                   scopes,
                   status,
                   created_at AS createdAt,
                   revoked_at AS revokedAt
              FROM api_key
             WHERE tenant_id = #{tenantId}
             ORDER BY id DESC
            """)
    List<ApiKeyRow> listByTenant(@Param("tenantId") long tenantId);
}
