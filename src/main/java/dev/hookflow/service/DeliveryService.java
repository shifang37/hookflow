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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class DeliveryService {

    /** Upper bound for one batch replay: the console's page size, and one operator selection. */
    public static final int MAX_BATCH_REPLAY = 100;

    private final DeliveryMapper deliveryMapper;
    private final DeliveryAttemptMapper attemptMapper;
    private final AuditLogMapper auditLogMapper;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public DeliveryService(DeliveryMapper deliveryMapper,
                           DeliveryAttemptMapper attemptMapper,
                           AuditLogMapper auditLogMapper,
                           OutboxWriter outboxWriter,
                           TransactionTemplate transactionTemplate) {
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
        this.auditLogMapper = auditLogMapper;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
    }

    public record Page(List<Delivery> items, long total) {
    }

    /**
     * One line of a batch replay. Exactly one of {@code status} (replayed) and
     * {@code rejection} (not replayed) is set.
     */
    public record ReplayOutcome(long deliveryId, String status, ErrorCode rejection, String message) {

        public boolean replayed() {
            return rejection == null;
        }
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
        return replayInTransaction(tenantId, deliveryId);
    }

    private Delivery replayInTransaction(long tenantId, long deliveryId) {
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

    /**
     * Replays each id in <b>its own transaction</b>, in request order, and reports every id
     * individually.
     *
     * <p>Not one transaction for the batch: these are independent operator retries (the
     * console's checkbox selection), and one delivery having moved on — a late worker
     * settling it, another operator replaying it — must not roll back the nine that were
     * fine. That is also why the caller gets {@code replayed} or the rejection reason per
     * id instead of a boolean for the request: a partial success that says "false" is the
     * kind of answer that makes an operator replay everything again.
     *
     * <p>Only {@link ApiException} is turned into a per-id rejection. A database failure
     * propagates: it is not a fact about one delivery, and swallowing it would report
     * "rejected" for rows that were never looked at.
     */
    public List<ReplayOutcome> replayBatch(long tenantId, List<Long> deliveryIds) {
        List<ReplayOutcome> outcomes = new ArrayList<>(deliveryIds.size());
        for (long deliveryId : deliveryIds) {
            try {
                Delivery replayed = transactionTemplate.execute(
                        status -> replayInTransaction(tenantId, deliveryId));
                outcomes.add(new ReplayOutcome(deliveryId, replayed.status().name(), null, null));
            } catch (ApiException rejected) {
                // The transaction has already rolled back; the reason is all that is left to say.
                outcomes.add(new ReplayOutcome(deliveryId, null, rejected.errorCode(),
                        rejected.getMessage()));
            }
        }
        return outcomes;
    }
}
