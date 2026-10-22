package dev.hookflow.backpressure;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.fairness.InFlight;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Load shedding, without a web layer: what is refused, with which status code, and
 * whether the caller is told when to come back.
 */
class BacklogGateTest {

    private static final long TENANT = 5L;

    private final OutboxMapper outboxMapper = mock(OutboxMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a tenant above its own watermark gets 429 + Retry-After, not 503")
    void tenantOverWatermarkIsShedAlone() {
        givenBacklog(20, 20);
        BacklogGate gate = gate(1_000_000, 10);

        assertThatThrownBy(() -> gate.check(TENANT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_BACKLOG_EXCEEDED);
                    assertThat(e.errorCode().status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("a platform above its watermark gets 503: nobody can be served")
    void platformOverWatermarkIsShedGlobally() {
        givenBacklog(1_000, 0);
        BacklogGate gate = gate(500, 10_000);

        assertThatThrownBy(() -> gate.check(TENANT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.OVER_CAPACITY);
                    assertThat(e.errorCode().status().value()).isEqualTo(503);
                    assertThat(e.retryAfterSeconds()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("below the watermarks nothing is refused")
    void healthyBacklogIsAccepted() {
        givenBacklog(3, 3);
        BacklogGate gate = gate(1_000_000, 10_000);

        assertThatCode(() -> gate.check(TENANT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("watermark 0 disables that check entirely")
    void zeroDisablesTheCheck() {
        givenBacklog(1_000_000, 1_000_000);
        BacklogGate gate = gate(0, 0);

        assertThatCode(() -> gate.check(TENANT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the backlog is counted once per interval, not once per request")
    void countsAreCached() {
        givenBacklog(0, 0);
        BacklogGate gate = gate(1_000_000, 10_000);

        for (int i = 0; i < 50; i++) {
            gate.check(TENANT);
        }

        // The ingest path must not run a COUNT(*) per event.
        verify(outboxMapper, times(1)).countUnpublished();
    }

    private void givenBacklog(long global, long tenant) {
        when(outboxMapper.countUnpublished()).thenReturn(global);
        when(outboxMapper.countUnpublishedByTenants()).thenReturn(List.of(new InFlight(TENANT, tenant)));
    }

    private BacklogGate gate(long outboxWatermark, long tenantWatermark) {
        HookFlowProperties properties = new HookFlowProperties(
                null, null,
                new HookFlowProperties.Relay(50, 10, 300, 60, 7, 20_000),
                null, null, null, null,
                new HookFlowProperties.Fairness(true, 200),
                new HookFlowProperties.Backpressure(outboxWatermark, tenantWatermark, 7, 60_000),
                new HookFlowProperties.Worker("defer"),
                null);
        return new BacklogGate(outboxMapper, properties, meterRegistry);
    }
}
