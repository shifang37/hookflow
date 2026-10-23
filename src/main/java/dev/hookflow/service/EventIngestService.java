package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.EventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <h2>Batch ingest (v0.4)</h2>
 *
 * <p>{@link #ingestBatch} writes the whole batch — events, deliveries, outbox — in
 * <b>one</b> transaction with a fixed number of statements (insert events, select
 * them back, insert deliveries, select them back, insert outbox) rather than
 * three round trips per event. The speedup is from fewer transactions and fewer
 * round trips, <em>not</em> from more concurrency: a batch still executes on one
 * connection, in one commit.
 *
 * <p>Partial duplicates are not an error: keys that already exist are classified
 * with one indexed read and come back as replays pointing at the original event,
 * and a key repeated inside one request resolves to the event created by its first
 * occurrence. A lost race against a concurrent request aborts the transaction and
 * is retried by re-reading the winners, which is the batch version of the same
 * compensation read the single path uses.
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    /**
     * How many times a batch may lose an idempotency race before the caller sees an
     * error. A retry is safe (nothing from the rolled-back attempt is visible) and
     * the alternative — failing a client whose keys are valid — creates duplicates
     * at the caller. Three attempts is far beyond the ~one interleaving a real
     * concurrent batch produces.
     */
    private static final int MAX_BATCH_ATTEMPTS = 3;

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

    /** One event of a batch, payload already normalised to JSON by the controller. */
    public record BatchItem(String eventType, String payloadJson, String idempotencyKey) {
    }

    /**
     * Per-item outcome.
     *
     * @param index          position in the request, so the caller can pair results
     *                       with what it sent without relying on ordering
     * @param idempotentReplay true when this item did not create a new event: either
     *                       the key already existed, or it repeated an earlier item
     *                       of the same batch
     */
    public record BatchResult(int index, long eventId, int deliveryCount, boolean idempotentReplay) {
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

    /**
     * Ingests up to {@code items.size()} events in one transaction.
     *
     * @return one result per item, in request order
     */
    public List<BatchResult> ingestBatch(long tenantId, List<BatchItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        DuplicateKeyException lastRace = null;
        for (int attempt = 1; attempt <= MAX_BATCH_ATTEMPTS; attempt++) {
            try {
                return ingestBatchOnce(tenantId, items);
            } catch (DuplicateKeyException lostRace) {
                lastRace = lostRace;
                log.warn("A batch of {} events lost an idempotency race (attempt {}/{}); "
                        + "re-reading the winners", items.size(), attempt, MAX_BATCH_ATTEMPTS);
            }
        }
        return resolveAfterLostRace(tenantId, items, lastRace);
    }

    private List<BatchResult> ingestBatchOnce(long tenantId, List<BatchItem> items) {
        // One item per key: a key repeated inside the batch is answered by the event
        // its first occurrence creates, not by a second insert.
        Map<String, BatchItem> firstItemByKey = new LinkedHashMap<>();
        for (BatchItem item : items) {
            firstItemByKey.putIfAbsent(item.idempotencyKey(), item);
        }

        Map<String, Event> existing = loadEvents(tenantId, firstItemByKey.keySet());
        Map<String, Long> eventIdByKey = new HashMap<>();
        Map<Long, Integer> deliveryCounts = new HashMap<>();
        for (Map.Entry<String, Event> entry : existing.entrySet()) {
            eventIdByKey.put(entry.getKey(), entry.getValue().id());
        }
        loadDeliveryCounts(tenantId,
                existing.values().stream().map(Event::id).toList(), deliveryCounts);

        Map<String, BatchItem> toInsert = new LinkedHashMap<>();
        for (Map.Entry<String, BatchItem> entry : firstItemByKey.entrySet()) {
            if (!existing.containsKey(entry.getKey())) {
                toInsert.put(entry.getKey(), entry.getValue());
            }
        }
        if (!toInsert.isEmpty()) {
            transactionTemplate.executeWithoutResult(status ->
                    insertBatchInTransaction(tenantId, toInsert, eventIdByKey, deliveryCounts));
        }
        return toResults(items, eventIdByKey, deliveryCounts, existing.keySet());
    }

    /**
     * The one transaction of a batch: events -> deliveries -> outbox, each phase a
     * single multi-row statement plus (for the two tables with generated ids) one
     * read-back.
     *
     * <p>The read-backs are what make this work with immutable records and MySQL: a
     * multi-row INSERT does not hand the generated ids back through MyBatis here, but
     * inside the same transaction the rows are visible by their natural keys
     * (idempotency key, event+endpoint).
     */
    private void insertBatchInTransaction(long tenantId,
                                          Map<String, BatchItem> toInsert,
                                          Map<String, Long> eventIdByKey,
                                          Map<Long, Integer> deliveryCounts) {
        List<Map<String, Object>> eventRows = new ArrayList<>(toInsert.size());
        for (BatchItem item : toInsert.values()) {
            Map<String, Object> row = new LinkedHashMap<>(4);
            row.put("eventType", item.eventType());
            row.put("payload", item.payloadJson());
            row.put("idempotencyKey", item.idempotencyKey());
            eventRows.add(row);
        }
        eventMapper.insertBatch(tenantId, eventRows);

        Map<String, Event> inserted = new HashMap<>();
        for (Event event : eventMapper.findByTenantAndIdempotencyKeys(tenantId, toInsert.keySet())) {
            inserted.put(event.idempotencyKey(), event);
        }
        if (!inserted.keySet().containsAll(toInsert.keySet())) {
            throw new IllegalStateException("batch event insert did not persist every row");
        }

        List<Endpoint> endpoints = endpointMapper.listActiveByTenant(tenantId);
        List<Map<String, Object>> deliveryRows = new ArrayList<>();
        List<Long> eventIds = new ArrayList<>(toInsert.size());
        Map<Long, List<Endpoint>> targetsByEvent = new HashMap<>();
        for (String key : toInsert.keySet()) {
            Event event = inserted.get(key);
            eventIdByKey.put(key, event.id());
            eventIds.add(event.id());
            List<Endpoint> targets = endpoints.stream()
                    .filter(endpoint -> endpoint.subscribesTo(event.eventType()))
                    .toList();
            targetsByEvent.put(event.id(), targets);
            for (Endpoint endpoint : targets) {
                Map<String, Object> row = new LinkedHashMap<>(4);
                row.put("eventId", event.id());
                row.put("endpointId", endpoint.id());
                row.put("maxAttempts", endpoint.maxAttempts());
                deliveryRows.add(row);
            }
        }

        if (deliveryRows.isEmpty()) {
            // Nothing subscribes to any type in this batch: valid, zero deliveries.
            for (Long eventId : eventIds) {
                deliveryCounts.put(eventId, 0);
            }
            return;
        }

        deliveryMapper.insertBatch(tenantId, deliveryRows);

        Map<String, Long> deliveryIdByPair = new HashMap<>();
        for (Delivery delivery : deliveryMapper.listByEventIds(tenantId, eventIds)) {
            deliveryIdByPair.put(pairOf(delivery.eventId(), delivery.endpointId()), delivery.id());
        }

        List<OutboxWriter.FreshDelivery> pending = new ArrayList<>(deliveryRows.size());
        for (String key : toInsert.keySet()) {
            Event event = inserted.get(key);
            List<Endpoint> targets = targetsByEvent.get(event.id());
            for (Endpoint endpoint : targets) {
                Long deliveryId = deliveryIdByPair.get(pairOf(event.id(), endpoint.id()));
                if (deliveryId == null) {
                    throw new IllegalStateException("batch delivery insert did not persist every row");
                }
                pending.add(new OutboxWriter.FreshDelivery(event.id(), endpoint.id(), deliveryId));
            }
            deliveryCounts.put(event.id(), targets.size());
        }
        outboxWriter.appendBatch(tenantId, pending);
    }

    /**
     * Last resort after every retry lost: if the winners are readable now, the batch
     * is answered as replays (nothing of ours persisted, so nothing was lost). If a
     * key still has no row, the caller gets the original conflict — the same
     * pathological case the single-event path documents.
     */
    private List<BatchResult> resolveAfterLostRace(long tenantId, List<BatchItem> items,
                                                   DuplicateKeyException lostRace) {
        Set<String> keys = new LinkedHashSet<>();
        for (BatchItem item : items) {
            keys.add(item.idempotencyKey());
        }
        Map<String, Event> existing = loadEvents(tenantId, keys);
        if (!existing.keySet().containsAll(keys)) {
            throw lostRace;
        }
        Map<String, Long> eventIdByKey = new HashMap<>();
        Map<Long, Integer> deliveryCounts = new HashMap<>();
        existing.forEach((key, event) -> eventIdByKey.put(key, event.id()));
        loadDeliveryCounts(tenantId, existing.values().stream().map(Event::id).toList(), deliveryCounts);
        return toResults(items, eventIdByKey, deliveryCounts, existing.keySet());
    }

    private List<BatchResult> toResults(List<BatchItem> items,
                                        Map<String, Long> eventIdByKey,
                                        Map<Long, Integer> deliveryCounts,
                                        Set<String> preExisting) {
        List<BatchResult> results = new ArrayList<>(items.size());
        Set<String> seenKeys = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            String key = items.get(index).idempotencyKey();
            Long eventId = eventIdByKey.get(key);
            if (eventId == null) {
                throw new IllegalStateException("no event id resolved for batch item " + index);
            }
            boolean repeatedInBatch = !seenKeys.add(key);
            results.add(new BatchResult(index, eventId,
                    deliveryCounts.getOrDefault(eventId, 0),
                    preExisting.contains(key) || repeatedInBatch));
        }
        return results;
    }

    private Map<String, Event> loadEvents(long tenantId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Event> byKey = new HashMap<>();
        for (Event event : eventMapper.findByTenantAndIdempotencyKeys(tenantId, keys)) {
            byKey.put(event.idempotencyKey(), event);
        }
        return byKey;
    }

    /**
     * Fills {@code deliveryCounts} for a set of events with one aggregate query.
     * Events with no delivery are simply absent (the caller defaults to 0).
     */
    private void loadDeliveryCounts(long tenantId, Collection<Long> eventIds, Map<Long, Integer> into) {
        if (eventIds.isEmpty()) {
            return;
        }
        for (DeliveryMapper.EventCount row : deliveryMapper.countByEventIds(tenantId, eventIds)) {
            if (row.id() != null) {
                into.put(row.id(), (int) row.total());
            }
        }
    }

    private static String pairOf(long eventId, long endpointId) {
        return eventId + ":" + endpointId;
    }

    public Event requireEvent(long tenantId, long eventId) {
        return eventMapper.findByIdAndTenant(eventId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    private int deliveryCount(long tenantId, long eventId) {
        return (int) deliveryMapper.count(tenantId, eventId, null);
    }
}
