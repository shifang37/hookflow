package dev.hookflow.relay;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.outbox.OutboxMessage;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.fairness.WeightedRoundRobin;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.messaging.DeliveryPublisher;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves rows from the outbox table to the broker.
 *
 * <p>Claiming happens in a short transaction that ends before any network I/O:
 * rows are locked with {@code FOR UPDATE SKIP LOCKED} and flipped to PUBLISHING.
 * Publishing then happens outside that transaction, and each row is marked
 * PUBLISHED only after the broker confirms.
 *
 * <p>The residual crash window — "confirmed, then died before the update" — is
 * recovered by {@link OutboxSweeper}, which re-publishes stale PUBLISHING rows.
 * That is the concrete reason the end-to-end guarantee is at-least-once.
 *
 * <h2>v0.3: the relay is also the scheduler</h2>
 *
 * <p>In v0.2 this class published rows in primary-key order, which is the same as
 * "in the order the tenants happened to submit". A tenant that submitted 10 000
 * events therefore owned the entire broker queue, and every other tenant's work
 * waited behind it — no consumer-side trick can fix that, because the small
 * tenant's message is never even delivered to a consumer.
 *
 * <p>v0.3 changes <em>which row may become a broker message</em>:
 *
 * <ol>
 *   <li><b>Admission</b> — a row is only published while its tenant is below
 *       {@code tenant.max_in_flight} and its endpoint is below
 *       {@code endpoint.max_concurrency}. The backlog therefore stays in MySQL,
 *       where it is invisible to other tenants, instead of in the broker.</li>
 *   <li><b>Weighted round robin</b> — among the rows that are admissible, the
 *       publish order is interleaved by tenant weight rather than sorted by id,
 *       so a weight-1 tenant never waits more than a couple of positions behind a
 *       weight-100 tenant.</li>
 * </ol>
 *
 * <p>Both are disabled by {@code hookflow.fairness.enabled=false}, which is how
 * {@code FairnessIT} measures the starvation ratio before and after.
 */
@Component
@Profile({"relay", "all"})
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Bounded so one scheduled tick can never spin forever on a very deep backlog. */
    private static final int MAX_ROUNDS_PER_TICK = 20;

    /**
     * Upper bound on the {@code NOT IN (...)} lists.
     *
     * <p>Larger lists are truncated, never dropped: a truncated entry is still
     * caught by the per-row admission check, so the worst case is a wasted scan,
     * not an over-budget publish.
     */
    private static final int MAX_BLOCKED_IDS = 128;

    private final OutboxMapper outboxMapper;
    private final DeliveryPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;
    private final AdmissionControl admission;
    private final WeightedRoundRobin<Long> scheduler = new WeightedRoundRobin<>();

    public OutboxRelay(OutboxMapper outboxMapper,
                       DeliveryPublisher publisher,
                       ObjectMapper objectMapper,
                       TransactionTemplate transactionTemplate,
                       HookFlowProperties properties,
                       MeterRegistry meterRegistry,
                       AdmissionControl admission) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.admission = admission;
    }

    @Scheduled(fixedDelayString = "${hookflow.relay.interval-ms:300}")
    public void relayOnce() {
        boolean fair = properties.fairness().enabled();
        if (fair) {
            admission.refreshIfStale();
        }
        for (int round = 0; round < MAX_ROUNDS_PER_TICK; round++) {
            List<OutboxMessage> batch = claimBatch(fair);
            if (batch.isEmpty()) {
                return;
            }
            boolean brokerHealthy = publishBatch(batch);
            if (!brokerHealthy) {
                return;
            }
        }
    }

    /** Locks, selects and flips a batch in one short transaction, before any I/O. */
    private List<OutboxMessage> claimBatch(boolean fair) {
        List<Long> blockedTenants = fair ? bounded(admission.blockedTenants()) : List.of();
        List<Long> blockedEndpoints = fair ? bounded(admission.blockedEndpoints()) : List.of();

        List<OutboxMessage> claimed = transactionTemplate.execute(status -> {
            List<OutboxMessage> rows = outboxMapper.lockSchedulableBatch(
                    properties.relay().batchSize(), blockedTenants, blockedEndpoints);
            if (rows.isEmpty()) {
                return List.of();
            }
            List<OutboxMessage> selected = fair ? schedule(rows) : rows;
            if (selected.isEmpty()) {
                return List.of();
            }
            outboxMapper.markPublishing(selected.stream().map(OutboxMessage::id).toList());
            return selected;
        });
        return claimed == null ? List.of() : claimed;
    }

    /**
     * Picks the publish order for one locked batch.
     *
     * <p>Rows are grouped per tenant and handed out in weighted round robin order.
     * A row that fails the per-row admission check is dropped from this batch and
     * stays NEW, so the next round (or the next tick) can pick it up when the
     * budget frees. The tenant is <em>not</em> skipped wholesale, because its other
     * rows may target endpoints that are still below their cap.
     */
    private List<OutboxMessage> schedule(List<OutboxMessage> rows) {
        Map<Long, Deque<OutboxMessage>> byTenant = new LinkedHashMap<>();
        for (OutboxMessage row : rows) {
            long tenantId = row.tenantId() == null ? 0L : row.tenantId();
            byTenant.computeIfAbsent(tenantId, key -> new ArrayDeque<>()).add(row);
        }

        List<OutboxMessage> ordered = new ArrayList<>(rows.size());
        while (!byTenant.isEmpty()) {
            Long tenantId = scheduler.next(byTenant.keySet(), admission::weightOf);
            if (tenantId == null) {
                break;
            }
            Deque<OutboxMessage> queue = byTenant.get(tenantId);
            OutboxMessage row = queue.poll();
            if (queue.isEmpty()) {
                byTenant.remove(tenantId);
                // The phase is per batch on purpose: fairness *between* batches is
                // the budget's job, not the selector's.
                scheduler.forget(tenantId);
            }
            if (row == null) {
                continue;
            }
            long endpointId = row.endpointId() == null ? 0L : row.endpointId();
            if (!admission.admit(tenantId, endpointId)) {
                meterRegistry.counter("hookflow.fairness.skipped_over_budget").increment();
                continue;
            }
            admission.markPublished(tenantId, endpointId);
            ordered.add(row);
            meterRegistry.counter("hookflow.fairness.admitted").increment();
        }
        return ordered;
    }

    private static List<Long> bounded(Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_BLOCKED_IDS) {
            return ids.stream().sorted().limit(MAX_BLOCKED_IDS).toList();
        }
        return List.copyOf(ids);
    }

    /** @return false when the broker looks unhealthy, so the loop stops instead of spinning */
    private boolean publishBatch(List<OutboxMessage> batch) {
        for (OutboxMessage row : batch) {
            DeliveryMessage message;
            try {
                message = objectMapper.readValue(row.payload(), DeliveryMessage.class);
            } catch (RuntimeException e) {
                log.error("Outbox row {} has an unreadable payload; leaving it for inspection", row.id(), e);
                meterRegistry.counter("hookflow.outbox.corrupt").increment();
                continue;
            }
            try {
                publisher.publish(message);
                outboxMapper.markPublished(row.id());
                meterRegistry.counter("hookflow.outbox.published").increment();
            } catch (DeliveryPublisher.PublishFailedException e) {
                outboxMapper.releaseBack(row.id());
                if (e.nacked()) {
                    // Queue length cap hit. Expected under overload: the backlog
                    // belongs in MySQL, and the row is already back there.
                    meterRegistry.counter("hookflow.outbox.publish_rejected").increment();
                    log.warn("Broker refused outbox row {} (queue at its length cap); "
                            + "row is back to NEW and the backlog stays in MySQL", row.id());
                } else {
                    meterRegistry.counter("hookflow.outbox.publish_failed").increment();
                    log.warn("Publish failed for outbox row {}: {}", row.id(), e.getMessage());
                }
                return false;
            }
        }
        return true;
    }
}
