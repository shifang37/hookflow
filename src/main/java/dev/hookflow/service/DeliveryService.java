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

import java.time.LocalDateTime;
import java.util.Arrays;
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

    /**
     * @param endpointId optional; scopes the list to one callback target
     * @param status     optional; must name a {@link DeliveryStatus} exactly
     * @param from       optional inclusive lower bound on {@code created_at}
     * @param to         optional exclusive upper bound on {@code created_at}
     */
    public Page list(long tenantId, Long eventId, Long endpointId, String status,
                     LocalDateTime from, LocalDateTime to, int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.invalid("from must be before to");
        }
        String normalizedStatus = requireKnownStatus(status);
        return new Page(
                deliveryMapper.list(tenantId, eventId, endpointId, normalizedStatus, from, to,
                        size, page * size),
                deliveryMapper.count(tenantId, eventId, endpointId, normalizedStatus, from, to));
    }

    /**
     * An unknown status used to be passed straight into the {@code WHERE} clause and
     * quietly returned an empty page, which reads as "this tenant has no failures"
     * rather than "you typed it wrong". Rejecting it is the whole point of having a
     * closed enum.
     */
    private static String requireKnownStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(status).name();
        } catch (IllegalArgumentException unknown) {
            throw ApiException.invalid("status must be one of " + Arrays.toString(DeliveryStatus.values()));
        }
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
