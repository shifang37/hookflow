package dev.hookflow.service;

import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.AuditLogMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deadlock discovered by the fairness test (docs/failures/0007) must be
 * survived, not just documented: a pause that fails on the first lock conflict is
 * an operator clicking the button again, and an operator clicking again is not a
 * recovery strategy.
 *
 * <p>Since v0.4 the retry loop is the shared {@link RetryOnLockConflict} component
 * rather than a copy inside {@code EndpointService}. This test therefore asserts
 * two things: pause still survives the conflict, and it goes through the component
 * (visible as the {@code hookflow.db.lock_conflict} metric). The component's own
 * contract is covered by {@code RetryOnLockConflictTest}.
 */
class EndpointPauseRetryTest {

    private static final long TENANT = 1L;
    private static final long ENDPOINT = 2L;

    private final EndpointMapper endpointMapper = mock(EndpointMapper.class);
    private final DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a deadlock victim is retried and the pause still succeeds")
    void pauseSurvivesADeadlock() {
        // Call 1: inside the first (rolled back) attempt. Call 2: the retry, which
        // finds the row still ACTIVE because the deadlock rolled the update back.
        // Call 3: the read that returns the paused endpoint.
        EndpointService service = service(List.of(Endpoint.ACTIVE, Endpoint.ACTIVE, Endpoint.PAUSED));
        when(deliveryMapper.cancelPendingForEndpoint(ENDPOINT, TENANT))
                .thenThrow(new DeadlockLoserDataAccessException(
                        "Deadlock found when trying to get lock", new RuntimeException("1213")))
                .thenReturn(7);

        Endpoint paused = service.pause(TENANT, ENDPOINT);

        assertThat(paused.status()).isEqualTo(Endpoint.PAUSED);
        verify(deliveryMapper, times(2)).cancelPendingForEndpoint(ENDPOINT, TENANT);
        // The audit row must be written once, not once per attempt.
        verify(auditLogMapper, times(1)).insert(anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyString());
        assertThat(conflicts())
                .as("the conflict must be counted as an operation-tagged metric")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("when every attempt conflicts the pause fails instead of pretending it worked")
    void pauseGivesUpAfterTheRetryBudget() {
        EndpointService service = service(List.of(Endpoint.ACTIVE));
        when(deliveryMapper.cancelPendingForEndpoint(ENDPOINT, TENANT))
                .thenThrow(new DeadlockLoserDataAccessException(
                        "Deadlock found when trying to get lock", new RuntimeException("1213")));

        assertThatThrownBy(() -> service.pause(TENANT, ENDPOINT))
                .isInstanceOf(DeadlockLoserDataAccessException.class);
        verify(deliveryMapper, times(3)).cancelPendingForEndpoint(ENDPOINT, TENANT);
        assertThat(conflicts()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("pausing an already paused endpoint does not touch deliveries")
    void pausingTwiceIsANoOp() {
        EndpointService service = service(List.of(Endpoint.PAUSED));

        Endpoint paused = service.pause(TENANT, ENDPOINT);

        assertThat(paused.status()).isEqualTo(Endpoint.PAUSED);
        verify(deliveryMapper, times(0)).cancelPendingForEndpoint(anyLong(), anyLong());
        assertThat(conflicts()).isZero();
    }

    private EndpointService service(List<String> statuses) {
        org.mockito.stubbing.OngoingStubbing<Optional<Endpoint>> stubbing =
                when(endpointMapper.findByIdAndTenant(ENDPOINT, TENANT));
        for (String status : statuses) {
            stubbing = stubbing.thenReturn(Optional.of(endpoint(status)));
        }
        stubbing.thenReturn(Optional.of(endpoint(statuses.getLast())));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        return new EndpointService(endpointMapper, deliveryMapper, auditLogMapper,
                mock(dev.hookflow.crypto.SecretCipher.class), properties(), mock(EgressGuard.class),
                transactionTemplate, new RetryOnLockConflict(meterRegistry));
    }

    private double conflicts() {
        Counter counter = meterRegistry.find("hookflow.db.lock_conflict")
                .tag("operation", "endpoint.pause").counter();
        return counter == null ? 0 : counter.count();
    }

    private static Endpoint endpoint(String status) {
        return new Endpoint(ENDPOINT, TENANT, "http://localhost:9099/hook", new byte[]{1}, (short) 1,
                "*", 8, 0, status, null, null);
    }

    private static HookFlowProperties properties() {
        return new HookFlowProperties(
                new HookFlowProperties.Delivery(1_000, 1_000, 512, 65_536),
                new HookFlowProperties.Lease(60, 3_600_000),
                new HookFlowProperties.Relay(50, 10, 300, 60, 7, 20_000),
                new HookFlowProperties.Retry(5_000, 3_600_000, 8),
                null, null, null,
                new HookFlowProperties.Fairness(true, 1_000),
                new HookFlowProperties.Backpressure(100_000, 10_000, 5, 500),
                new HookFlowProperties.Worker("defer"),
                null,
                new HookFlowProperties.Retention(7, 3_600_000));
    }
}
