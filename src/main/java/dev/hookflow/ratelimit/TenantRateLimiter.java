package dev.hookflow.ratelimit;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed-window per-tenant rate limiting in Redis.
 *
 * <p>The counter is the only thing Redis owns. Events themselves are never held
 * in Redis, so a Redis flush cannot lose customer data — the worst it does is
 * let a burst through.
 *
 * <p>When Redis is unreachable the behaviour is a deliberate policy choice, not
 * an accident: {@code hookflow.rate-limit.fail-open=true} keeps accepting events
 * (availability over protection), {@code false} rejects with 503 (protection over
 * availability). The chosen mode is logged at startup.
 */
@Component
public class TenantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiter.class);

    /**
     * INCR then set the TTL only on the first increment: one round trip, no
     * read-modify-write race, and the window cannot extend itself accidentally.
     */
    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return current
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);
    private static final long WINDOW_MS = 1000L;

    private final StringRedisTemplate redis;
    private final HookFlowProperties properties;

    public TenantRateLimiter(StringRedisTemplate redis, HookFlowProperties properties) {
        this.redis = redis;
        this.properties = properties;
        if (properties.rateLimit().enabled() && log.isInfoEnabled()) {
            log.info("Tenant rate limiting enabled (fail-open={})", properties.rateLimit().failOpen());
        }
    }

    /**
     * @throws ApiException {@link ErrorCode#RATE_LIMITED} when the window is exhausted,
     *                      or {@link ErrorCode#DEPENDENCY_UNAVAILABLE} in fail-closed mode
     */
    public void acquire(long tenantId, int limitPerSecond) {
        if (!properties.rateLimit().enabled() || limitPerSecond <= 0) {
            return;
        }
        String key = "hookflow:rl:tenant:" + tenantId;
        try {
            Long current = redis.execute(SCRIPT, List.of(key),
                    Integer.toString(limitPerSecond), Long.toString(WINDOW_MS));
            if (current != null && current > limitPerSecond) {
                throw new ApiException(ErrorCode.RATE_LIMITED,
                        "Rate limit of " + limitPerSecond + " events/s exceeded for this tenant");
            }
        } catch (DataAccessException e) {
            if (properties.rateLimit().failOpen()) {
                log.warn("Redis unavailable, allowing request (fail-open): {}", e.getMessage());
                return;
            }
            log.error("Redis unavailable, rejecting request (fail-closed): {}", e.getMessage());
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Rate limiter unavailable");
        }
    }
}
