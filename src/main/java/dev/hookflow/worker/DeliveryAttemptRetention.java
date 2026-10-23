package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prunes the delivery attempt history.
 *
 * <p>Shape copied from {@code relay/OutboxRetention} on purpose: bounded batches,
 * a scheduled tick, a counter, and {@code 0} meaning "off". The outbox got this in
 * v0.2; the attempt table did not, and it is the one that grows with every
 * <em>retry</em> as well as every delivery — the table that would silently become
 * the largest one in a production database.
 *
 * <p>The one thing this class must never do is delete a row that is still
 * {@code IN_PROGRESS}: {@code DeliveryProcessor.settle} finishes an attempt with
 * {@code WHERE ... AND status = 'IN_PROGRESS'}, so a deleted row turns that update
 * into a silent zero-row no-op and the outcome of a live attempt disappears. The
 * filter lives in the SQL ({@link DeliveryAttemptMapper#deleteFinishedBefore}), not
 * here, because that is the only place it cannot be bypassed.
 *
 * <p>The retention window is expected to be comfortably larger than
 * {@code hookflow.lease.ttl-seconds} (the longest an attempt can legitimately stay
 * IN_PROGRESS). That is a premise, not a fence: the null-safety of the delete comes
 * from the status filter alone, and a too-small window only means the history of a
 * crash-window attempt is pruned sooner. {@link #warnIfBelowLease()} makes a
 * misconfiguration loud instead of silent.
 */
@Component
@Profile({"worker", "all"})
public class DeliveryAttemptRetention {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAttemptRetention.class);
    private static final int BATCH_SIZE = 5000;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final long SECONDS_PER_DAY = 86_400L;

    private final DeliveryAttemptMapper attemptMapper;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean warnedAboutLease = new AtomicBoolean();

    public DeliveryAttemptRetention(DeliveryAttemptMapper attemptMapper,
                                    HookFlowProperties properties,
                                    MeterRegistry meterRegistry) {
        this.attemptMapper = attemptMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hookflow.retention.interval-ms:3600000}",
            initialDelayString = "${hookflow.retention.interval-ms:3600000}")
    public void prune() {
        HookFlowProperties.Retention retention = properties.retention();
        if (retention == null || retention.attemptDays() <= 0) {
            return;
        }
        int retentionDays = retention.attemptDays();
        warnIfBelowLease();

        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = attemptMapper.deleteFinishedBefore(retentionDays, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Pruned {} finished delivery attempts older than {} days", total, retentionDays);
            meterRegistry.counter("hookflow.attempt.pruned").increment(total);
        }
    }

    /**
     * A retention window shorter than lease TTL + 1 day means an attempt that is
     * in flight while the process dies can be pruned before the lease sweeper gets
     * to record it as {@code ABANDONED}. It is still never deleted while
     * IN_PROGRESS, so this is a warning about lost history, not about lost state.
     */
    private void warnIfBelowLease() {
        HookFlowProperties.Lease lease = properties.lease();
        if (lease == null || !warnedAboutLease.compareAndSet(false, true)) {
            return;
        }
        long minimumDays = 1 + (lease.ttlSeconds() + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY;
        if (properties.retention().attemptDays() < minimumDays) {
            log.warn("hookflow.retention.attempt-days={} is below lease.ttl-seconds + 1 day ({}); "
                            + "attempt history of a crashed in-flight attempt may be pruned early",
                    properties.retention().attemptDays(), minimumDays);
        }
    }
}
