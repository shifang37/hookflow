package dev.hookflow.persistence;

import dev.hookflow.domain.tenant.Tenant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface TenantMapper {

    @Insert("INSERT INTO tenant (name, rate_limit, status) VALUES (#{name}, #{rateLimit}, 'ACTIVE')")
    int insert(@Param("name") String name, @Param("rateLimit") int rateLimit);

    @Select("SELECT * FROM tenant WHERE id = #{id}")
    Optional<Tenant> findById(@Param("id") long id);

    @Select("SELECT * FROM tenant WHERE name = #{name}")
    Optional<Tenant> findByName(@Param("name") String name);
}
