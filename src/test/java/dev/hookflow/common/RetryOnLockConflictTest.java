package dev.hookflow.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared retry abstraction on its own, without services or MySQL.
 *
 * <p>{@code EndpointPauseRetryTest} proves that pause uses it;
 * {@code DemoResetRetryTest} proves that the demo reset does. This class proves the
 * contract both of them rely on: retry only lock conflicts, stop after the bound,
 * and make the deadlock rate visible as a metric.
 */
class RetryOnLockConflictTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a conflict is retried and the action runs again from the start")
    void retriesUntilTheActionSucceeds() {
        RetryOnLockConflict retry = new RetryOnLockConflict(meterRegistry);
        AtomicInteger runs = new AtomicInteger();

        String result = retry.execute("endpoint.pause", () -> {
            if (runs.incrementAndGet() == 1) {
                throw deadlock();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(runs).hasValue(2);
        assertThat(conflicts("endpoint.pause")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("lock wait timeouts are retried too, not just deadlock victims")
    void retriesCannotAcquireLock() {
        RetryOnLockConflict retry = new RetryOnLockConflict(meterRegistry);
        AtomicInteger runs = new AtomicInteger();

        retry.executeWithoutResult("demo.reset", () -> {
            if (runs.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("Lock wait timeout exceeded");
            }
        });

        assertThat(runs).hasValue(2);
        assertThat(conflicts("demo.reset")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("after the attempt budget the conflict is rethrown, not swallowed")
    void givesUpAfterThreeAttempts() {
        RetryOnLockConflict retry = new RetryOnLockConflict(meterRegistry);
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute("endpoint.pause", () -> {
            runs.incrementAndGet();
            throw deadlock();
        })).isInstanceOf(DeadlockLoserDataAccessException.class);

        assertThat(runs).as("3 attempts in total").hasValue(3);
        assertThat(conflicts("endpoint.pause"))
                .as("every conflict is counted, including the one that ended the retry loop")
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("a non-lock failure is never retried")
    void doesNotRetryOtherFailures() {
        RetryOnLockConflict retry = new RetryOnLockConflict(meterRegistry);
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute("endpoint.pause", () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("unique key violation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(runs).hasValue(1);
        assertThat(meterRegistry.find("hookflow.db.lock_conflict").counter()).isNull();
    }

    @Test
    @DisplayName("the metric lands in the Prometheus registry the actuator endpoint scrapes")
    void metricIsExposedToPrometheus() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RetryOnLockConflict retry = new RetryOnLockConflict(prometheus);

        try {
            retry.execute("endpoint.pause", () -> {
                throw deadlock();
            });
        } catch (DeadlockLoserDataAccessException expected) {
            // first attempt lost; a second one succeeds
        }
        retry.execute("endpoint.pause", () -> "ok");

        String scrape = prometheus.scrape();
        assertThat(scrape)
                .as("/actuator/prometheus exposes the same registry; this is its text format")
                .contains("hookflow_db_lock_conflict_total")
                .contains("operation=\"endpoint.pause\"");
    }

    private double conflicts(String operation) {
        Counter counter = meterRegistry.find("hookflow.db.lock_conflict")
                .tags("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    private static DeadlockLoserDataAccessException deadlock() {
        return new DeadlockLoserDataAccessException("Deadlock found when trying to get lock",
                new RuntimeException("1213"));
    }
}
