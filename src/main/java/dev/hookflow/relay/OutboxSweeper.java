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
 * Recovers the relay crash window.
 *
 * <p>A row in PUBLISHING means "a relay took it and we do not know whether the
 * broker confirmed". Waiting long enough and re-publishing is the only safe
 * choice: re-publishing may duplicate, never publishing may lose. Duplicates are
 * absorbed downstream by the attempt-count fence in the worker.
 */
@Component
@Profile({"relay", "all"})
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final OutboxMapper outboxMapper;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxSweeper(OutboxMapper outboxMapper, HookFlowProperties properties, MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hookflow.relay.sweep-interval-ms:30000}")
    public void recoverStalePublishing() {
        int staleSeconds = properties.relay().stalePublishingSeconds();
        int recovered = outboxMapper.recoverStalePublishing(staleSeconds, 500);
        if (recovered > 0) {
            log.warn("Re-queued {} outbox rows stuck in PUBLISHING for more than {}s (duplicates possible)",
                    recovered, staleSeconds);
            meterRegistry.counter("hookflow.outbox.recovered").increment(recovered);
        }
    }
}
