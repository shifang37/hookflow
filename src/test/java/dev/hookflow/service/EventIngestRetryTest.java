package dev.hookflow.service;

import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.domain.event.Event;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.EventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The call-site contract for {@link EventIngestService}: a deadlock against the
 * relay's outbox scan is retried as a whole transaction.
 *
 * <p>This is the {@code ReadApiIT.setUp} flake at the unit level. The retry
 * component itself is covered by {@code RetryOnLockConflictTest}; what this test
 * pins is that this call site uses it, and that the second attempt starts from a
 * clean read rather than carrying state from the rolled-back one.
 */
class EventIngestRetryTest {

    @Test
    @DisplayName("a deadlock on the ingest transaction is retried and creates one event")
    void deadlockIsRetried() {
        EventMapper eventMapper = mock(EventMapper.class);
        EndpointMapper endpointMapper = mock(EndpointMapper.class);
        DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        Event event = new Event(42L, 1L, "order.paid", "{}", "retry-key",
                LocalDateTime.of(2026, 11, 4, 0, 0));
        when(eventMapper.findByTenantAndIdempotencyKey(1L, "retry-key"))
                .thenReturn(Optional.empty(), Optional.of(event));
        when(endpointMapper.listActiveByTenant(1L)).thenReturn(List.of());

        AtomicInteger transactions = new AtomicInteger();
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            if (transactions.incrementAndGet() == 1) {
                throw new DeadlockLoserDataAccessException("relay vs ingest",
                        new SQLException("Deadlock found"));
            }
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        EventIngestService service = new EventIngestService(eventMapper, endpointMapper,
                deliveryMapper, outboxWriter, transactionTemplate,
                new RetryOnLockConflict(meterRegistry));

        EventIngestService.IngestResult result =
                service.ingest(1L, "order.paid", "{}", "retry-key");

        assertThat(result.eventId()).isEqualTo(42L);
        assertThat(result.deliveryCount()).isZero();
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(transactions).as("the rolled-back attempt and the successful retry")
                .hasValue(2);
        assertThat(meterRegistry.get("hookflow.db.lock_conflict")
                .tag("operation", "event.ingest")
                .counter()
                .count())
                .as("the conflict is visible, not silently swallowed")
                .isEqualTo(1.0);
    }
}
