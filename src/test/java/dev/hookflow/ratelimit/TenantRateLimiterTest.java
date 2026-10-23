package dev.hookflow.ratelimit;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The quota counter counts <b>events</b>, not requests.
 *
 * <p>That distinction is the whole reason the batch endpoint had to touch the
 * limiter at all: if a batch of 500 counted as one, a caller could send 500× its
 * quota through {@code /v1/events/batch} while the single-event endpoint kept
 * refusing at the same limit. These tests pin the permit count that reaches Redis.
 */
class TenantRateLimiterTest {

    private static final long TENANT = 7L;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("a single event increments the window by one")
    void singleEventAsksForOnePermit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        TenantRateLimiter limiter = limiter(true);

        limiter.acquire(TENANT, 100);

        verify(redis).execute(any(RedisScript.class), anyList(), eq("100"), eq("1000"), eq("1"));
    }

    @Test
    @DisplayName("a batch increments the window by the number of items it carries")
    void batchAsksForAllItsEvents() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(20L);
        TenantRateLimiter limiter = limiter(true);

        limiter.acquire(TENANT, 100, 20);

        verify(redis).execute(any(RedisScript.class), anyList(), eq("100"), eq("1000"), eq("20"));
    }

    @Test
    @DisplayName("a batch that would exceed the window is refused as a whole")
    void batchOverTheLimitIsRefused() {
        // 100 events/s, the window already holds 90, this request carries 20.
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(110L);
        TenantRateLimiter limiter = limiter(true);

        assertThatThrownBy(() -> limiter.acquire(TENANT, 100, 20))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("a batch that exactly fits the window is accepted")
    void batchAtTheBoundaryIsAccepted() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(100L);
        TenantRateLimiter limiter = limiter(true);

        assertThatCode(() -> limiter.acquire(TENANT, 100, 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disabled limiting never reaches Redis, whatever the batch size")
    void disabledLimiterDoesNotTouchRedis() {
        TenantRateLimiter limiter = limiter(false);

        limiter.acquire(TENANT, 100, 500);

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("a tenant with no quota (0) is never limited")
    void zeroLimitMeansUnlimited() {
        TenantRateLimiter limiter = limiter(true);

        limiter.acquire(TENANT, 0, 500);

        verifyNoInteractions(redis);
    }

    private TenantRateLimiter limiter(boolean enabled) {
        HookFlowProperties properties = new HookFlowProperties(
                null, null, null, null, null,
                new HookFlowProperties.RateLimit(enabled, true),
                null, null, null, null, null, null);
        return new TenantRateLimiter(redis, properties);
    }
}
