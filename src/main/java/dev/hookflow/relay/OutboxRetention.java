package dev.hookflow.relay;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes the outbox.
 *
 * <p>The outbox is an append-only log: one row per delivery attempt. Leaving it
 * alone means the table grows linearly with traffic forever, which eventually
 * hurts the {@code (status, id)} scan the relay depends on. Rows are deleted only
 * after they have been PUBLISHED for longer than the retention window, so an
 * incident can still be investigated from the table.
 */
@Component
@Profile({"relay", "all"})
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);
    private static final int BATCH_SIZE = 5000;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final OutboxMapper outboxMapper;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxRetention(OutboxMapper outboxMapper, HookFlowProperties properties, MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hookflow.relay.retention-interval-ms:3600000}",
            initialDelayString = "${hookflow.relay.retention-interval-ms:3600000}")
    public void prune() {
        int retentionDays = properties.relay().retentionDays();
        if (retentionDays <= 0) {
            return;
        }
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = outboxMapper.deletePublishedBefore(retentionDays, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Pruned {} published outbox rows older than {} days", total, retentionDays);
            meterRegistry.counter("hookflow.outbox.pruned").increment(total);
        }
    }
}
