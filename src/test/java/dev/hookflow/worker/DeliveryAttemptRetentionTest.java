package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pruning loop, without a database.
 *
 * <p>The part that matters here is not the SQL (that is {@code AttemptRetentionIT}
 * against real MySQL) but the two ways this job can be wrong: it must do nothing
 * when disabled, and it must stop after a bounded number of batches instead of
 * turning one tick into an unbounded DELETE loop.
 */
class DeliveryAttemptRetentionTest {

    private final DeliveryAttemptMapper attemptMapper = mock(DeliveryAttemptMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("attempt-days=0 disables pruning completely - no statement is even issued")
    void zeroDisablesPruning() {
        DeliveryAttemptRetention retention = retention(0);

        retention.prune();

        verifyNoInteractions(attemptMapper);
        assertThat(prunedCount()).isZero();
    }

    @Test
    @DisplayName("finished rows older than the window are pruned and counted")
    void prunesWithTheConfiguredWindow() {
        when(attemptMapper.deleteFinishedBefore(7, 5000)).thenReturn(3);
        DeliveryAttemptRetention retention = retention(7);

        retention.prune();

        verify(attemptMapper, times(1)).deleteFinishedBefore(7, 5000);
        assertThat(prunedCount())
                .as("the metric must reflect the rows actually deleted")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a full batch makes it continue, a short batch makes it stop")
    void keepsGoingUntilABatchComesBackShort() {
        when(attemptMapper.deleteFinishedBefore(7, 5000)).thenReturn(5000, 1200);
        DeliveryAttemptRetention retention = retention(7);

        retention.prune();

        verify(attemptMapper, times(2)).deleteFinishedBefore(7, 5000);
        assertThat(prunedCount()).isEqualTo(6200);
    }

    @Test
    @DisplayName("one tick never issues more than the configured maximum number of batches")
    void boundedNumberOfBatches() {
        when(attemptMapper.deleteFinishedBefore(anyInt(), anyInt())).thenReturn(5000);
        DeliveryAttemptRetention retention = retention(7);

        retention.prune();

        verify(attemptMapper, times(20)).deleteFinishedBefore(7, 5000);
    }

    @Test
    @DisplayName("nothing deleted means no counter is created")
    void nothingToDo() {
        when(attemptMapper.deleteFinishedBefore(7, 5000)).thenReturn(0);
        DeliveryAttemptRetention retention = retention(7);

        retention.prune();

        assertThat(meterRegistry.find("hookflow.attempt.pruned").counter()).isNull();
    }

    /**
     * Pins the shipped defaults from {@code application.yml} against the premise the
     * pruning SQL relies on: an attempt can only be IN_PROGRESS for one lease TTL, so
     * a window of at least TTL + 1 day can never race a live attempt. Reading the real
     * YAML (instead of copying the numbers here) means a future change to either value
     * fails this test instead of silently invalidating the reasoning in the migration.
     */
    @Test
    @DisplayName("the shipped window is above lease TTL + 1 day")
    void shippedRetentionWindowCoversTheLease() throws IOException {
        Map<String, Object> defaults = shippedDefaults();

        int attemptDays = Integer.parseInt(String.valueOf(defaults.get("hookflow.retention.attempt-days")));
        int leaseTtlSeconds = Integer.parseInt(String.valueOf(defaults.get("hookflow.lease.ttl-seconds")));

        long minimumDays = 1 + (leaseTtlSeconds + 86_400L - 1) / 86_400L;
        assertThat(attemptDays)
                .as("hookflow.retention.attempt-days must stay above lease TTL + 1 day")
                .isGreaterThanOrEqualTo((int) minimumDays);
    }

    private long prunedCount() {
        Counter counter = meterRegistry.find("hookflow.attempt.pruned").counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private DeliveryAttemptRetention retention(int attemptDays) {
        HookFlowProperties properties = new HookFlowProperties(
                null,
                new HookFlowProperties.Lease(60, 3_600_000),
                null, null, null, null, null, null, null, null, null,
                new HookFlowProperties.Retention(attemptDays, 3_600_000));
        return new DeliveryAttemptRetention(attemptMapper, properties, meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedDefaults() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        return (Map<String, Object>) sources.getFirst().getSource();
    }
}
