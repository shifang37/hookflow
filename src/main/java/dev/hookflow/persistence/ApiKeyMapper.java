package dev.hookflow.persistence;

import dev.hookflow.domain.apikey.ApiKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface ApiKeyMapper {

    @Insert("""
            INSERT INTO api_key (tenant_id, key_prefix, key_hash, scopes, status)
            VALUES (#{tenantId}, #{keyPrefix}, #{keyHash}, #{scopes}, 'ACTIVE')
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("keyPrefix") String keyPrefix,
               @Param("keyHash") String keyHash,
               @Param("scopes") String scopes);

    @Select("SELECT * FROM api_key WHERE key_prefix = #{prefix}")
    Optional<ApiKey> findByPrefix(@Param("prefix") String prefix);

    @Update("UPDATE api_key SET status = 'REVOKED', revoked_at = NOW(3) WHERE id = #{id} AND status = 'ACTIVE'")
    int revoke(@Param("id") long id);
}
