package dev.hookflow.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditLogMapper {

    @Insert("""
            INSERT INTO audit_log (tenant_id, actor, action, target_type, target_id, detail)
            VALUES (#{tenantId}, #{actor}, #{action}, #{targetType}, #{targetId}, #{detail})
            """)
    int insert(@Param("tenantId") long tenantId,
               @Param("actor") String actor,
               @Param("action") String action,
               @Param("targetType") String targetType,
               @Param("targetId") long targetId,
               @Param("detail") String detail);
}
