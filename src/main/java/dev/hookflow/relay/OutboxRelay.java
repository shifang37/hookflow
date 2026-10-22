package dev.hookflow.relay;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.outbox.OutboxMessage;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.messaging.DeliveryPublisher;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Moves rows from the outbox table to the broker.
 *
 * <p>Claiming happens in a short transaction that ends before any network I/O:
 * rows are locked with {@code FOR UPDATE SKIP LOCKED} and flipped to PUBLISHING.
 * Publishing then happens outside that transaction, and each row is marked
 * PUBLISHED only after the broker confirms.
 *
 * <p>The residual crash window — "confirmed, then died before the update" — is
 * recovered by {@link OutboxSweeper}, which re-publishes stale PUBLISHING rows.
 * That is the concrete reason the end-to-end guarantee is at-least-once.
 */
@Component
@Profile({"relay", "all"})
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMapper outboxMapper;
    private final DeliveryPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxRelay(OutboxMapper outboxMapper,
                       DeliveryPublisher publisher,
                       ObjectMapper objectMapper,
                       TransactionTemplate transactionTemplate,
                       HookFlowProperties properties,
                       MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hookflow.relay.interval-ms:300}")
    public void relayOnce() {
        int maxRounds = 20;
        for (int round = 0; round < maxRounds; round++) {
            List<OutboxMessage> batch = claimBatch();
            if (batch.isEmpty()) {
                return;
            }
            boolean brokerHealthy = publishBatch(batch);
            if (!brokerHealthy) {
                return;
            }
        }
    }

    /** Locks and flips a batch in one short transaction, before any I/O. */
    private List<OutboxMessage> claimBatch() {
        List<OutboxMessage> claimed = transactionTemplate.execute(status -> {
            List<OutboxMessage> rows = outboxMapper.lockBatch(properties.relay().batchSize());
            if (rows.isEmpty()) {
                return List.of();
            }
            outboxMapper.markPublishing(rows.stream().map(OutboxMessage::id).toList());
            return rows;
        });
        return claimed == null ? List.of() : claimed;
    }

    /** @return false when the broker looks unhealthy, so the loop stops instead of spinning */
    private boolean publishBatch(List<OutboxMessage> batch) {
        for (OutboxMessage row : batch) {
            DeliveryMessage message;
            try {
                message = objectMapper.readValue(row.payload(), DeliveryMessage.class);
            } catch (RuntimeException e) {
                log.error("Outbox row {} has an unreadable payload; leaving it for inspection", row.id(), e);
                meterRegistry.counter("hookflow.outbox.corrupt").increment();
                continue;
            }
            try {
                publisher.publish(message);
                outboxMapper.markPublished(row.id());
                meterRegistry.counter("hookflow.outbox.published").increment();
            } catch (DeliveryPublisher.PublishFailedException e) {
                log.warn("Publish failed for outbox row {}: {}", row.id(), e.getMessage());
                outboxMapper.releaseBack(row.id());
                meterRegistry.counter("hookflow.outbox.publish_failed").increment();
                return false;
            }
        }
        return true;
    }
}
