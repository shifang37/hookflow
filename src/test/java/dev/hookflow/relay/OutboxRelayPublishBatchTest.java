package dev.hookflow.relay;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.outbox.OutboxMessage;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.messaging.DeliveryPublisher;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's batch publish, at the unit that changed in v0.4 (B4).
 *
 * <p>The claim under test is not "it publishes faster" — that is measured, not
 * asserted (docs/performance.md §16). What is asserted here is the rule that makes
 * batching safe: <b>a row is marked PUBLISHED only if the broker acknowledged that
 * message</b>, and every row that was not acknowledged goes back to NEW in one
 * statement instead of being lost or silently marked done.
 */
class OutboxRelayPublishBatchTest {

    private final OutboxMapper outboxMapper = mock(OutboxMapper.class);
    private final DeliveryPublisher publisher = mock(DeliveryPublisher.class);
    private final AdmissionControl admission = mock(AdmissionControl.class);
    private final HookFlowProperties properties = mock(HookFlowProperties.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        // publishBatch only needs the mapper, the publisher and the registry; the
        // scheduler's collaborators are here because that is the real constructor.
        relay = new OutboxRelay(outboxMapper, publisher, objectMapper,
                mock(TransactionTemplate.class), properties, meterRegistry, admission);
    }

    @Test
    @DisplayName("every acked message is marked PUBLISHED in one statement, not one per row")
    void allAckedBecomesOneUpdate() {
        when(publisher.publishAll(anyList())).thenReturn(List.of(
                DeliveryPublisher.PublishOutcome.ACKED,
                DeliveryPublisher.PublishOutcome.ACKED,
                DeliveryPublisher.PublishOutcome.ACKED));

        boolean healthy = relay.publishBatch(rows(1L, 2L, 3L));

        assertThat(healthy).as("a fully acknowledged batch leaves the broker healthy").isTrue();
        verify(outboxMapper).markPublishedBatch(List.of(1L, 2L, 3L));
        verify(outboxMapper, never()).releaseBackBatch(anyList());
        assertThat(meterRegistry.counter("hookflow.outbox.published").count())
                .as("the counter counts rows, not batches - the throughput report reads it")
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("a nacked row goes back to NEW, the acked ones are still published, the tick stops")
    void mixOfAckAndNack() {
        when(publisher.publishAll(anyList())).thenReturn(List.of(
                DeliveryPublisher.PublishOutcome.ACKED,
                DeliveryPublisher.PublishOutcome.NACKED,
                DeliveryPublisher.PublishOutcome.ACKED));

        boolean healthy = relay.publishBatch(rows(11L, 12L, 13L));

        assertThat(healthy).as("a refusal means the queue is at its cap: stop the tick").isFalse();
        verify(outboxMapper).markPublishedBatch(List.of(11L, 13L));
        verify(outboxMapper).releaseBackBatch(List.of(12L));
        assertThat(meterRegistry.counter("hookflow.outbox.publish_rejected").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("hookflow.outbox.published").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("unconfirmed is not published: rows go back to NEW and nothing is marked done")
    void unconfirmedIsNotPublished() {
        when(publisher.publishAll(anyList())).thenReturn(List.of(
                DeliveryPublisher.PublishOutcome.FAILED,
                DeliveryPublisher.PublishOutcome.FAILED));

        boolean healthy = relay.publishBatch(rows(21L, 22L));

        assertThat(healthy).isFalse();
        verify(outboxMapper, never()).markPublishedBatch(anyList());
        verify(outboxMapper).releaseBackBatch(List.of(21L, 22L));
        assertThat(meterRegistry.counter("hookflow.outbox.publish_failed").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("hookflow.outbox.published").count()).isZero();
    }

    @Test
    @DisplayName("an unreadable payload is skipped, not published and not fatal for the batch")
    void unreadablePayloadIsSkipped() {
        OutboxMessage broken = new OutboxMessage(31L, "DELIVERY", 1L, 1, 1L, 1L,
                "{not json", "NEW", null, 0, null, null);
        OutboxMessage good = row(32L);
        when(publisher.publishAll(anyList())).thenReturn(List.of(DeliveryPublisher.PublishOutcome.ACKED));

        boolean healthy = relay.publishBatch(List.of(broken, good));

        assertThat(healthy).isTrue();
        verify(outboxMapper).markPublishedBatch(List.of(32L));
        assertThat(meterRegistry.counter("hookflow.outbox.corrupt").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nothing to publish means no broker call at all")
    void emptyBatchDoesNotTouchTheBroker() {
        assertThat(relay.publishBatch(List.of())).isTrue();
        verify(publisher, never()).publishAll(anyList());
    }

    private List<OutboxMessage> rows(long... ids) {
        return LongStream.of(ids).mapToObj(this::row).toList();
    }

    private OutboxMessage row(long id) {
        DeliveryMessage payload = new DeliveryMessage(id * 10, 1, 7L, 8L, 9L, null);
        return new OutboxMessage(id, "DELIVERY", id * 10, 1, 7L, 9L,
                objectMapper.writeValueAsString(payload), "PUBLISHING", null, 1, null, null);
    }
}
