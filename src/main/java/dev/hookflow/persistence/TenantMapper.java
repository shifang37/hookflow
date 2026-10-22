package dev.hookflow.persistence;

import dev.hookflow.domain.fairness.TenantBudget;
import dev.hookflow.domain.tenant.Tenant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TenantMapper {

    @Insert("INSERT INTO tenant (name, rate_limit, status) VALUES (#{name}, #{rateLimit}, 'ACTIVE')")
    int insert(@Param("name") String name, @Param("rateLimit") int rateLimit);

    @Select("SELECT * FROM tenant WHERE id = #{id}")
    Optional<Tenant> findById(@Param("id") long id);

    @Select("SELECT * FROM tenant WHERE name = #{name}")
    Optional<Tenant> findByName(@Param("name") String name);

    /**
     * The scheduling budget of every tenant that may still receive work.
     *
     * <p>A suspended tenant is deliberately absent: it is not a candidate for
     * scheduling, so it must not occupy a slot in the weighted round robin.
     */
    @Select("SELECT id, max_in_flight, weight FROM tenant WHERE status = 'ACTIVE'")
    List<TenantBudget> listBudgets();

    @Update("UPDATE tenant SET max_in_flight = #{maxInFlight}, weight = #{weight} WHERE id = #{id}")
    int updateBudget(@Param("id") long id,
                     @Param("maxInFlight") int maxInFlight,
                     @Param("weight") int weight);
}
