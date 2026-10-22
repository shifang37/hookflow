package dev.hookflow.service;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.AuditLogMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 */
class EndpointPauseRetryTest {

    private static final long TENANT = 1L;
    private static final long ENDPOINT = 2L;

    private final EndpointMapper endpointMapper = mock(EndpointMapper.class);
    private final DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

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
    }

    @Test
    @DisplayName("pausing an already paused endpoint does not touch deliveries")
    void pausingTwiceIsANoOp() {
        EndpointService service = service(List.of(Endpoint.PAUSED));

        Endpoint paused = service.pause(TENANT, ENDPOINT);

        assertThat(paused.status()).isEqualTo(Endpoint.PAUSED);
        verify(deliveryMapper, times(0)).cancelPendingForEndpoint(anyLong(), anyLong());
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
                transactionTemplate);
    }

    private static Endpoint endpoint(String status) {
        return new Endpoint(ENDPOINT, TENANT, "http://localhost:9099/hook", new byte[]{1}, (short) 1,
                "*", 8, 0, status, null, null);
    }

    private static HookFlowProperties properties() {
        return new HookFlowProperties(
                null, null,
                new HookFlowProperties.Relay(50, 10, 300, 60, 7, 20_000),
                new HookFlowProperties.Retry(5_000, 3_600_000, 8),
                null, null, null,
                new HookFlowProperties.Fairness(true, 1_000),
                new HookFlowProperties.Backpressure(100_000, 10_000, 5, 500),
                new HookFlowProperties.Worker("defer"),
                null);
    }
}
