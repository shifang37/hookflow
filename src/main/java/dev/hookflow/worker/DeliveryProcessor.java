package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.EventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Executes one delivery attempt.
 *
 * <p>Three phases, deliberately separated:
 * <ol>
 *   <li><b>claim</b> (transaction): compare-and-set the row into DELIVERING and
 *       write an IN_PROGRESS attempt. No network call happens here.</li>
 *   <li><b>call</b> (no transaction): the HTTP request. Holding a row lock across
 *       a slow customer endpoint would serialise the whole platform on the
 *       slowest receiver.</li>
 *   <li><b>settle</b> (transaction): write the attempt outcome and move the
 *       delivery, fenced by the lease token.</li>
 * </ol>
 *
 * <p>If the process dies between 1 and 3, the lease expires, a sweeper reclaims
 * the row, and the attempt row stays visible as ABANDONED. Nothing is lost and
 * nothing is silently duplicated.
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final DeliveryMapper deliveryMapper;
    private final DeliveryAttemptMapper attemptMapper;
    private final EventMapper eventMapper;
    private final EndpointMapper endpointMapper;
    private final HttpDeliverer deliverer;
    private final TransactionTemplate transactionTemplate;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    public DeliveryProcessor(DeliveryMapper deliveryMapper,
                             DeliveryAttemptMapper attemptMapper,
                             EventMapper eventMapper,
                             EndpointMapper endpointMapper,
                             HttpDeliverer deliverer,
                             TransactionTemplate transactionTemplate,
                             HookFlowProperties properties,
                             MeterRegistry meterRegistry) {
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.endpointMapper = endpointMapper;
        this.deliverer = deliverer;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return true when the message is done with (handled, superseded, or
     *         non-retryable), false when the broker should redeliver it
     */
    public boolean process(DeliveryMessage message) {
        Delivery delivery = deliveryMapper.findById(message.deliveryId()).orElse(null);
        if (delivery == null) {
            log.warn("Delivery {} no longer exists, dropping message", message.deliveryId());
            return true;
        }

        String leaseToken = UUID.randomUUID().toString();
        Integer claimed = transactionTemplate.execute(status -> {
            int updated = deliveryMapper.claim(
                    delivery.id(), message.attemptNo() - 1, leaseToken, properties.lease().ttlSeconds());
            if (updated == 0) {
                return 0;
            }
            attemptMapper.insertInProgress(delivery.id(), message.attemptNo());
            return 1;
        });

        if (claimed == null || claimed == 0) {
            // Duplicate message, or another worker already advanced this delivery.
            // This is the normal, expected outcome of at-least-once delivery.
            meterRegistry.counter("hookflow.delivery.duplicate_skipped").increment();
            log.debug("Attempt {} of delivery {} was already claimed; skipping",
                    message.attemptNo(), delivery.id());
            return true;
        }

        Event event = eventMapper.findById(delivery.eventId()).orElse(null);
        Endpoint endpoint = endpointMapper.findById(delivery.endpointId()).orElse(null);
        if (event == null || endpoint == null) {
            settleAbandoned(delivery.id(), message.attemptNo(), "MISSING_PARENT");
            return true;
        }
        if (!endpoint.isActive()) {
            transactionTemplate.executeWithoutResult(status ->
                    deliveryMapper.markCanceled(delivery.id(), leaseToken));
            attemptMapper.finish(delivery.id(), message.attemptNo(), DeliveryAttempt.FAILED,
                    null, "endpoint paused", "ENDPOINT_PAUSED", 0);
            return true;
        }

        HttpDeliverer.Outcome outcome = deliverer.deliver(endpoint, event, delivery, message.attemptNo());
        settle(delivery, message.attemptNo(), leaseToken, outcome);
        return true;
    }

    private void settle(Delivery delivery, int attemptNo, String leaseToken, HttpDeliverer.Outcome outcome) {
        transactionTemplate.executeWithoutResult(status -> {
            if (outcome.success()) {
                attemptMapper.finish(delivery.id(), attemptNo, DeliveryAttempt.SUCCEEDED,
                        outcome.statusCode(), outcome.responsePreview(), null, outcome.durationMs());
                deliveryMapper.markSucceeded(delivery.id(), leaseToken, attemptNo, outcome.statusCode());
                meterRegistry.counter("hookflow.delivery.succeeded").increment();
                return;
            }

            boolean budgetLeft = attemptNo < delivery.maxAttempts();
            if (!outcome.retryable() || !budgetLeft) {
                attemptMapper.finish(delivery.id(), attemptNo, DeliveryAttempt.FAILED,
                        outcome.statusCode(), outcome.responsePreview(), outcome.errorClass(),
                        outcome.durationMs());
                deliveryMapper.markDead(delivery.id(), leaseToken, attemptNo,
                        outcome.statusCode(), outcome.errorClass());
                meterRegistry.counter("hookflow.delivery.dead").increment();
                return;
            }

            attemptMapper.finish(delivery.id(), attemptNo, DeliveryAttempt.FAILED,
                    outcome.statusCode(), outcome.responsePreview(), outcome.errorClass(),
                    outcome.durationMs());
            Backoff backoff = new Backoff(
                    properties.retry().baseDelayMs(), properties.retry().maxDelayMs());
            long delayMicros = backoff.delayForAttempt(attemptNo) * 1_000L;
            deliveryMapper.markRetryWait(delivery.id(), leaseToken, attemptNo, delayMicros,
                    outcome.statusCode(), outcome.errorClass());
            meterRegistry.counter("hookflow.delivery.retry_scheduled").increment();
        });
    }

    private void settleAbandoned(long deliveryId, int attemptNo, String reason) {
        log.error("Delivery {} has no event/endpoint parent ({}); marking dead", deliveryId, reason);
        attemptMapper.finish(deliveryId, attemptNo, DeliveryAttempt.FAILED, null, null, reason, 0);
        Delivery current = deliveryMapper.findById(deliveryId).orElse(null);
        if (current != null && current.status() == DeliveryStatus.DELIVERING) {
            // No lease token known here; the lease sweeper will reclaim it.
            log.warn("Delivery {} left for the lease sweeper", deliveryId);
        }
    }
}
