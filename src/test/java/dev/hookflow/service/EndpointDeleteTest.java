package dev.hookflow.service;

import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.crypto.SecretCipher;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an endpoint is the one irreversible operation in the console, so the parts that
 * make it safe are asserted here rather than discovered in production: the queue is
 * cancelled exactly once, the row is marked (not removed), and a lock conflict is retried
 * under its own operation tag.
 *
 * <p>The database side — CHECK constraint, url_key uniqueness, fan-out exclusion — needs
 * real MySQL and is covered by {@code EndpointDeleteIT}.
 */
class EndpointDeleteTest {

    private static final long TENANT = 1L;
    private static final long ENDPOINT = 2L;

    private final EndpointMapper endpointMapper = mock(EndpointMapper.class);
    private final DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("delete marks the row, cancels the queue once and writes one audit row")
    void marksAndCancels() {
        when(endpointMapper.findByIdAndTenant(ENDPOINT, TENANT))
                .thenReturn(Optional.of(endpoint(Endpoint.ACTIVE)));
        when(endpointMapper.markDeleted(ENDPOINT, TENANT)).thenReturn(1);
        when(deliveryMapper.cancelPendingForEndpoint(ENDPOINT, TENANT)).thenReturn(5);

        service().delete(TENANT, ENDPOINT);

        verify(endpointMapper).markDeleted(ENDPOINT, TENANT);
        verify(deliveryMapper, times(1)).cancelPendingForEndpoint(ENDPOINT, TENANT);
        verify(auditLogMapper).insert(anyLong(), anyString(), eq("endpoint.delete"), anyString(),
                anyLong(), anyString());
        assertThat(conflicts()).isZero();
    }

    @Test
    @DisplayName("deleting an endpoint that no longer exists is a 404, not a second cancel")
    void missingEndpointIsNotFound() {
        when(endpointMapper.findByIdAndTenant(ENDPOINT, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(TENANT, ENDPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(endpointMapper, never()).markDeleted(anyLong(), anyLong());
        verify(deliveryMapper, never()).cancelPendingForEndpoint(anyLong(), anyLong());
    }

    @Test
    @DisplayName("losing the CAS race to a concurrent delete is a 404, not a second audit row")
    void losingTheRaceIsNotFound() {
        when(endpointMapper.findByIdAndTenant(ENDPOINT, TENANT))
                .thenReturn(Optional.of(endpoint(Endpoint.ACTIVE)));
        // require() saw the row, the guarded UPDATE matched nothing.
        when(endpointMapper.markDeleted(ENDPOINT, TENANT)).thenReturn(0);

        assertThatThrownBy(() -> service().delete(TENANT, ENDPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(deliveryMapper, never()).cancelPendingForEndpoint(anyLong(), anyLong());
        verify(auditLogMapper, never()).insert(anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyString());
    }

    @Test
    @DisplayName("a deadlock victim is replayed, and the audit row is written only by the attempt that committed")
    void deleteSurvivesADeadlock() {
        // Two reads: the failed attempt, then the retry.
        when(endpointMapper.findByIdAndTenant(ENDPOINT, TENANT))
                .thenReturn(Optional.of(endpoint(Endpoint.ACTIVE)))
                .thenReturn(Optional.of(endpoint(Endpoint.ACTIVE)));
        when(endpointMapper.markDeleted(ENDPOINT, TENANT)).thenReturn(1, 1);
        when(deliveryMapper.cancelPendingForEndpoint(ENDPOINT, TENANT))
                .thenThrow(new DeadlockLoserDataAccessException(
                        "Deadlock found when trying to get lock", new RuntimeException("1213")))
                .thenReturn(3);

        service().delete(TENANT, ENDPOINT);

        verify(deliveryMapper, times(2)).cancelPendingForEndpoint(ENDPOINT, TENANT);
        verify(auditLogMapper, times(1)).insert(anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyString());
        assertThat(conflicts())
                .as("the conflict is counted under the delete operation, not under pause")
                .isEqualTo(1.0);
    }

    private EndpointService service() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        return new EndpointService(endpointMapper, deliveryMapper, auditLogMapper,
                mock(SecretCipher.class), properties(), mock(EgressGuard.class),
                transactionTemplate, new RetryOnLockConflict(meterRegistry));
    }

    private double conflicts() {
        Counter counter = meterRegistry.find("hookflow.db.lock_conflict")
                .tag("operation", "endpoint.delete").counter();
        return counter == null ? 0 : counter.count();
    }

    private static Endpoint endpoint(String status) {
        return new Endpoint(ENDPOINT, TENANT, "https://example.test/hook", new byte[]{1}, (short) 1,
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
