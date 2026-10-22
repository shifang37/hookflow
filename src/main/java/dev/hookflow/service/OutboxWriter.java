package dev.hookflow.service;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.outbox.OutboxMessage;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.tracing.TraceContextSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends "publish this delivery attempt" rows.
 *
 * <p>The uniqueness key is {@code (aggregate_type, aggregate_id, attempt_no)}.
 * That is what makes a retry round idempotent: attempt 3 can be scheduled exactly
 * once even if two schedulers race, while attempt 4 is still allowed.
 *
 * <p>If the row already exists but was published long enough ago that no attempt
 * has happened, it is <b>re-armed</b> instead of rejected. Without that, a lost
 * broker message would strand the delivery in RETRY_WAIT permanently — the
 * uniqueness key would block every future attempt at re-scheduling it.
 */
@Component
public class OutboxWriter {

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final HookFlowProperties properties;
    private final TraceContextSupport traceContext;

    public OutboxWriter(OutboxMapper outboxMapper,
                        ObjectMapper objectMapper,
                        HookFlowProperties properties,
                        TraceContextSupport traceContext) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.traceContext = traceContext;
    }

    /**
     * @return true when a new row was created or an existing one was re-armed,
     *         false when this attempt is already in flight (nothing to do)
     */
    public boolean append(long tenantId, long eventId, long endpointId, long deliveryId, int attemptNo) {
        DeliveryMessage message = new DeliveryMessage(deliveryId, attemptNo, tenantId, eventId,
                endpointId, traceContext.currentTraceparent());
        String payload = objectMapper.writeValueAsString(message);
        try {
            outboxMapper.insert(OutboxMessage.AGGREGATE_DELIVERY, deliveryId, attemptNo, payload,
                    tenantId, endpointId);
            return true;
        } catch (DuplicateKeyException alreadyQueued) {
            return outboxMapper.rearmStalePublished(OutboxMessage.AGGREGATE_DELIVERY, deliveryId,
                    attemptNo, properties.relay().stalePublishingSeconds()) == 1;
        }
    }
}
