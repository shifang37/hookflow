package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryAttempt;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.AuditLogMapper;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeliveryService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryAttemptMapper attemptMapper;
    private final AuditLogMapper auditLogMapper;
    private final OutboxWriter outboxWriter;

    public DeliveryService(DeliveryMapper deliveryMapper,
                           DeliveryAttemptMapper attemptMapper,
                           AuditLogMapper auditLogMapper,
                           OutboxWriter outboxWriter) {
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
        this.auditLogMapper = auditLogMapper;
        this.outboxWriter = outboxWriter;
    }

    public record Page(List<Delivery> items, long total) {
    }

    public Page list(long tenantId, Long eventId, String status, int page, int size) {
        return new Page(
                deliveryMapper.list(tenantId, eventId, status, size, page * size),
                deliveryMapper.count(tenantId, eventId, status));
    }

    public Delivery require(long tenantId, long deliveryId) {
        return deliveryMapper.findByIdAndTenant(deliveryId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Delivery"));
    }

    public List<DeliveryAttempt> attempts(long tenantId, long deliveryId) {
        require(tenantId, deliveryId);
        return attemptMapper.listByDelivery(deliveryId);
    }

    /**
     * Puts a DEAD delivery back in the queue with exactly one extra attempt
     * budget. The attempt counter is not reset, so the attempt history stays
     * truthful; {@code max_attempts} becomes {@code attempt_count + 1}.
     *
     * @throws ApiException {@link ErrorCode#ILLEGAL_TRANSITION} when the delivery is not DEAD
     */
    @Transactional
    public Delivery replay(long tenantId, long deliveryId) {
        Delivery delivery = require(tenantId, deliveryId);
        if (delivery.status() != DeliveryStatus.DEAD) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "Only DEAD deliveries can be replayed (current: " + delivery.status() + ")");
        }
        if (deliveryMapper.replay(deliveryId, tenantId) == 0) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "Delivery changed state concurrently");
        }
        outboxWriter.append(tenantId, delivery.eventId(), delivery.endpointId(), delivery.id(),
                delivery.nextAttemptNo());
        auditLogMapper.insert(tenantId, "api-key", "delivery.replay", "delivery", deliveryId,
                "{\"attemptNo\":" + delivery.nextAttemptNo() + "}");
        return require(tenantId, deliveryId);
    }
}
