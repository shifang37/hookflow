package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.EventMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Accepts an event and fans it out to matching endpoints — all in one MySQL
 * transaction, together with the outbox rows that will make it publishable.
 *
 * <p>Idempotency is enforced by the unique key {@code (tenant_id, idempotency_key)},
 * not by a pre-flight SELECT: two concurrent identical requests both attempt the
 * insert, one loses, and the loser returns the winner's event id.
 *
 * <p>The compensation read deliberately happens <b>outside</b> the failed
 * transaction. Under MySQL's default REPEATABLE READ the snapshot was taken
 * before the winner committed, so a SELECT inside the losing transaction cannot
 * see the winner's row and would turn a routine race into a 500. See
 * {@code docs/failures/0001-idempotent-race-snapshot.md}.
 */
@Service
public class EventIngestService {

    private final EventMapper eventMapper;
    private final EndpointMapper endpointMapper;
    private final DeliveryMapper deliveryMapper;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public EventIngestService(EventMapper eventMapper,
                              EndpointMapper endpointMapper,
                              DeliveryMapper deliveryMapper,
                              OutboxWriter outboxWriter,
                              TransactionTemplate transactionTemplate) {
        this.eventMapper = eventMapper;
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
    }

    public record IngestResult(long eventId, int deliveryCount, boolean idempotentReplay) {
    }

    /**
     * @throws DuplicateKeyException only in the pathological case where the winner
     *                               is no longer readable after a lost race
     */
    public IngestResult ingest(long tenantId, String eventType, String payloadJson, String idempotencyKey) {
        try {
            return transactionTemplate.execute(status ->
                    ingestInTransaction(tenantId, eventType, payloadJson, idempotencyKey));
        } catch (DuplicateKeyException lostRace) {
            // The losing transaction has been rolled back by now, so this read runs
            // on a fresh connection and can observe the winner's committed row.
            Event winner = eventMapper.findByTenantAndIdempotencyKey(tenantId, idempotencyKey)
                    .orElseThrow(() -> lostRace);
            return new IngestResult(winner.id(), deliveryCount(tenantId, winner.id()), true);
        }
    }

    private IngestResult ingestInTransaction(long tenantId, String eventType,
                                             String payloadJson, String idempotencyKey) {
        Event existing = eventMapper.findByTenantAndIdempotencyKey(tenantId, idempotencyKey).orElse(null);
        if (existing != null) {
            return new IngestResult(existing.id(), deliveryCount(tenantId, existing.id()), true);
        }

        eventMapper.insert(tenantId, eventType, payloadJson, idempotencyKey);

        Event event = eventMapper.findByTenantAndIdempotencyKey(tenantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("event insert did not persist"));

        List<Endpoint> endpoints = endpointMapper.listActiveByTenant(tenantId).stream()
                .filter(endpoint -> endpoint.subscribesTo(eventType))
                .toList();

        for (Endpoint endpoint : endpoints) {
            deliveryMapper.insert(tenantId, event.id(), endpoint.id(), endpoint.maxAttempts());
            long deliveryId = deliveryMapper.findByEventAndEndpoint(event.id(), endpoint.id())
                    .orElseThrow(() -> new IllegalStateException("delivery insert did not persist"))
                    .id();
            outboxWriter.append(tenantId, event.id(), endpoint.id(), deliveryId, 1);
        }
        return new IngestResult(event.id(), endpoints.size(), false);
    }

    public Event requireEvent(long tenantId, long eventId) {
        return eventMapper.findByIdAndTenant(eventId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    private int deliveryCount(long tenantId, long eventId) {
        return (int) deliveryMapper.count(tenantId, eventId, null);
    }
}
