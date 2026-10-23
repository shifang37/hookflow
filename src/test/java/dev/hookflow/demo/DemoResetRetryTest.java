package dev.hookflow.demo;

import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.service.DeliveryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The demo console's reset button deletes from four tables and can lose a lock
 * conflict against the ingest path while it does (docs/failures/0007 — the console
 * was the second hand-written copy of that retry loop).
 *
 * <p>What is asserted here is that the reset goes through the shared
 * {@link RetryOnLockConflict} and reports the conflict as a metric, so a future
 * third copy of the loop cannot be added without this test failing to cover it.
 */
class DemoResetRetryTest {

    private final DemoRegistry registry = mock(DemoRegistry.class);
    private final BusinessSimulator simulator = mock(BusinessSimulator.class);
    private final DemoReceiver receiver = mock(DemoReceiver.class);
    private final DemoMapper demoMapper = mock(DemoMapper.class);
    private final OutboxMapper outboxMapper = mock(OutboxMapper.class);
    private final DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
    private final DeliveryService deliveryService = mock(DeliveryService.class);
    private final AdmissionControl admission = mock(AdmissionControl.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a lock conflict inside the delete block is retried and the reset still completes")
    void resetSurvivesALockConflict() throws Exception {
        givenDemoTenants();
        when(demoMapper.deleteAttempts(any())).thenReturn(3);
        when(demoMapper.deleteOutbox(any())).thenReturn(2);
        when(demoMapper.deleteDeliveries(any()))
                .thenThrow(new DeadlockLoserDataAccessException(
                        "Deadlock found when trying to get lock", new RuntimeException("1213")))
                .thenReturn(1);
        when(demoMapper.deleteEvents(any())).thenReturn(1);

        Map<String, Object> result = controller().reset();

        assertThat(result).containsEntry("attempts", 3)
                .containsEntry("outbox", 2)
                .containsEntry("deliveries", 1)
                .containsEntry("events", 1);
        verify(demoMapper, times(2)).deleteDeliveries(any());
        assertThat(conflicts()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("without a conflict the four deletes run once and no lock metric appears")
    void resetWithoutConflictIsSinglePass() throws Exception {
        givenDemoTenants();
        when(demoMapper.deleteAttempts(any())).thenReturn(1);
        when(demoMapper.deleteOutbox(any())).thenReturn(1);
        when(demoMapper.deleteDeliveries(any())).thenReturn(1);
        when(demoMapper.deleteEvents(any())).thenReturn(1);

        controller().reset();

        verify(demoMapper, times(1)).deleteDeliveries(any());
        assertThat(meterRegistry.find("hookflow.db.lock_conflict").counter()).isNull();
    }

    @Test
    @DisplayName("non-lock failures are not retried by the component itself")
    void nonLockFailureIsNotTreatedAsALockConflict() throws Exception {
        givenDemoTenants();
        // The FK race that the multi-round loop exists for: the retry component must
        // not swallow it, so the round loop sees it and tries another round. A
        // non-empty count after the failed round is what makes the loop continue.
        when(demoMapper.statusCounts(any()))
                .thenReturn(List.of(new DemoMapper.StatusCount(1L, "PENDING", 1L)))
                .thenReturn(List.of());
        when(demoMapper.deleteAttempts(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk race"))
                .thenReturn(1);
        when(demoMapper.deleteOutbox(any())).thenReturn(1);
        when(demoMapper.deleteDeliveries(any())).thenReturn(1);
        when(demoMapper.deleteEvents(any())).thenReturn(1);

        Map<String, Object> result = controller().reset();

        assertThat(result).containsEntry("attempts", 1);
        verify(demoMapper, times(2)).deleteAttempts(any());
        assertThat(meterRegistry.find("hookflow.db.lock_conflict").counter()).isNull();
    }

    private void givenDemoTenants() {
        when(registry.tenants()).thenReturn(List.of(
                new DemoTenant(1L, "Acme", "acme", "hk_a", 10L, "*", 32, 1),
                new DemoTenant(2L, "Quick", "quick", "hk_q", 20L, "order.paid", 32, 1)));
        // Empty status counts means "the tables are empty" -> one round is enough.
        when(demoMapper.statusCounts(any())).thenReturn(List.of());
    }

    private DemoController controller() {
        return new DemoController(registry, simulator, receiver, demoMapper, outboxMapper,
                deliveryMapper, deliveryService, admission, meterRegistry, null,
                new RetryOnLockConflict(meterRegistry));
    }

    private double conflicts() {
        Counter counter = meterRegistry.find("hookflow.db.lock_conflict")
                .tag("operation", "demo.reset").counter();
        return counter == null ? 0 : counter.count();
    }
}
