package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.service.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-enqueues work that the in-memory pipeline lost.
 *
 * <p>Three distinct recovery paths, all of them driven by MySQL state rather than
 * broker state:
 * <ul>
 *   <li><b>due retries</b> — RETRY_WAIT rows whose backoff elapsed.</li>
 *   <li><b>expired leases</b> — DELIVERING rows whose worker stopped reporting.</li>
 *   <li><b>stuck pending</b> — PENDING rows that never got published, e.g. because
 *       a broker message was lost after an acknowledgement. This is what makes
 *       "losing a broker message" not equal "losing a delivery".</li>
 * </ul>
 */
@Component
@Profile({"worker", "all"})
public class DeliveryRecovery {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRecovery.class);

    private final DeliveryMapper deliveryMapper;
    private final DeliveryAttemptMapper attemptMapper;
    private final OutboxWriter outboxWriter;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    public DeliveryRecovery(DeliveryMapper deliveryMapper,
                            DeliveryAttemptMapper attemptMapper,
                            OutboxWriter outboxWriter,
                            HookFlowProperties properties,
                            MeterRegistry meterRegistry) {
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hookflow.retry.scan-interval-ms:1000}")
    public void enqueueDueRetries() {
        List<Delivery> due = deliveryMapper.findDueRetries(200);
        for (Delivery delivery : due) {
            if (outboxWriter.append(delivery.tenantId(), delivery.eventId(), delivery.endpointId(),
                    delivery.id(), delivery.nextAttemptNo())) {
                meterRegistry.counter("hookflow.recovery.retry_enqueued").increment();
            }
        }
    }

    @Scheduled(fixedDelayString = "${hookflow.lease.sweep-interval-ms:15000}")
    public void reclaimExpiredLeases() {
        List<Delivery> expired = deliveryMapper.findExpiredLeases(100);
        for (Delivery delivery : expired) {
            attemptMapper.abandon(delivery.id(), delivery.attemptCount());
            if (deliveryMapper.reclaimExpiredLease(delivery.id()) > 0) {
                meterRegistry.counter("hookflow.recovery.lease_reclaimed").increment();
                log.warn("Lease expired for delivery {} at attempt {}; reclaimed",
                        delivery.id(), delivery.attemptCount());
                outboxWriter.append(delivery.tenantId(), delivery.eventId(), delivery.endpointId(),
                        delivery.id(), delivery.nextAttemptNo());
            }
        }
    }

    @Scheduled(fixedDelayString = "${hookflow.retry.stuck-scan-interval-ms:30000}")
    public void requeueStuckPending() {
        int graceSeconds = properties.relay().stalePublishingSeconds();
        List<Delivery> stuck = deliveryMapper.findStuckPending(graceSeconds, 200);
        for (Delivery delivery : stuck) {
            if (outboxWriter.append(delivery.tenantId(), delivery.eventId(), delivery.endpointId(),
                    delivery.id(), delivery.nextAttemptNo())) {
                meterRegistry.counter("hookflow.recovery.stuck_requeued").increment();
                log.info("Delivery {} looked stuck in PENDING; re-enqueued attempt {}",
                        delivery.id(), delivery.nextAttemptNo());
            }
        }
    }
}
