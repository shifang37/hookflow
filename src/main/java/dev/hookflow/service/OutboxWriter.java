package dev.hookflow.service;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.outbox.OutboxMessage;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.tracing.TraceContextSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One delivery of a freshly ingested batch: attempt 1, no row exists yet. */
    public record FreshDelivery(long eventId, long endpointId, long deliveryId) {
    }

    /**
     * Appends the first attempt of many new deliveries in one statement.
     *
     * <p>Batch ingest creates event, delivery and outbox rows in a single
     * transaction, so every row here is new by construction and the
     * "re-arm an existing row" logic of {@link #append} cannot apply. A duplicate key
     * would mean the transaction is inconsistent, and letting it roll the batch back
     * is the correct response.
     *
     * <p>All rows share the request's trace context, so a whole batch shows up as
     * children of the one API call that submitted it.
     */
    public void appendBatch(long tenantId, List<FreshDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        String traceparent = traceContext.currentTraceparent();
        List<Map<String, Object>> rows = new ArrayList<>(deliveries.size());
        for (FreshDelivery delivery : deliveries) {
            DeliveryMessage message = new DeliveryMessage(delivery.deliveryId(), 1, tenantId,
                    delivery.eventId(), delivery.endpointId(), traceparent);
            Map<String, Object> row = new LinkedHashMap<>(6);
            row.put("deliveryId", delivery.deliveryId());
            row.put("attemptNo", 1);
            row.put("payload", objectMapper.writeValueAsString(message));
            row.put("tenantId", tenantId);
            row.put("endpointId", delivery.endpointId());
            rows.add(row);
        }
        outboxMapper.insertBatch(rows);
    }
}
