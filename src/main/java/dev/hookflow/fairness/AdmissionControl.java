package dev.hookflow.fairness;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.fairness.EndpointCap;
import dev.hookflow.domain.fairness.InFlight;
import dev.hookflow.domain.fairness.TenantBudget;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether one more message of a given tenant / endpoint may be put on
 * the wire.
 *
 * <p><b>The budget, not the queue, is the fairness mechanism.</b> A tenant may
 * have at most {@code tenant.max_in_flight} deliveries in flight. Because the
 * scheduler refuses to publish past that budget, the number of that tenant's
 * messages in front of anybody else's is bounded by the budget itself — not by
 * how many events the tenant submitted. That is what turns "10 000 queued
 * events" into "at most N queued events plus a backlog in MySQL that is
 * invisible to other tenants".
 *
 * <p>The counts come from MySQL, so the view is shared by every relay instance.
 * Two deliberate approximations keep it cheap:
 *
 * <ol>
 *   <li>The snapshot is re-read every {@code hookflow.fairness.refresh-interval-ms},
 *       not on every decision. Between refreshes the scheduler adds its own
 *       publications to the local counts, so it can only ever <em>under</em>-admit:
 *       a message it just published already counts against the budget even though
 *       the worker has not claimed it yet.</li>
 *   <li>A delivery stops counting when the snapshot is refreshed after it left
 *       DELIVERING; until then it keeps consuming budget. Slightly conservative,
 *       never unsafe.</li>
 * </ol>
 *
 * <p>Redis is not involved on purpose: a correctness fence that disappears when
 * Redis is flushed would be worse than no fence at all (see ADR-0006).
 */
@Component
@Profile({"relay", "worker", "all"})
public class AdmissionControl {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControl.class);

    private final TenantMapper tenantMapper;
    private final EndpointMapper endpointMapper;
    private final DeliveryMapper deliveryMapper;
    private final HookFlowProperties properties;

    /** Local, monotonic-since-refresh publication counters (see class comment). */
    private final Map<Long, AtomicLong> tenantPublished = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> endpointPublished = new ConcurrentHashMap<>();

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastRefreshNanos;

    public AdmissionControl(TenantMapper tenantMapper,
                            EndpointMapper endpointMapper,
                            DeliveryMapper deliveryMapper,
                            HookFlowProperties properties) {
        this.tenantMapper = tenantMapper;
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.properties = properties;
    }

    /**
     * A consistent-enough view of "who is at their budget right now".
     *
     * @param budgets        tenant id -> budget; absent means uncapped
     * @param tenantInFlight tenant id -> DELIVERING count at refresh time
     * @param endpointCaps   endpoint id -> cap; absent means uncapped
     * @param endpointInFlight endpoint id -> DELIVERING count at refresh time
     */
    record Snapshot(Map<Long, TenantBudget> budgets,
                    Map<Long, Long> tenantInFlight,
                    Map<Long, Integer> endpointCaps,
                    Map<Long, Long> endpointInFlight) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Re-reads the budgets and in-flight counts, at most once per refresh interval. */
    public void refreshIfStale() {
        long intervalNanos = Math.max(1, properties.fairness().refreshIntervalMs()) * 1_000_000L;
        long now = System.nanoTime();
        if (now - lastRefreshNanos < intervalNanos) {
            return;
        }
        synchronized (this) {
            if (now - lastRefreshNanos < intervalNanos) {
                return;
            }
            refresh();
            lastRefreshNanos = now;
        }
    }

    /** Forces a refresh; used at startup and by tests that manipulate rows by hand. */
    public synchronized void refresh() {
        List<TenantBudget> budgets = tenantMapper.listBudgets();
        Map<Long, TenantBudget> budgetMap = new HashMap<>();
        for (TenantBudget budget : budgets) {
            budgetMap.put(budget.id(), budget);
        }

        Map<Long, Integer> caps = new HashMap<>();
        for (EndpointCap cap : endpointMapper.listConcurrencyCaps()) {
            caps.put(cap.id(), cap.maxConcurrency());
        }

        Map<Long, Long> tenantCounts = toMap(deliveryMapper.countInFlightByTenant());
        Map<Long, Long> endpointCounts = toMap(deliveryMapper.countInFlightByEndpoint());

        snapshot = new Snapshot(Map.copyOf(budgetMap), Map.copyOf(tenantCounts),
                Map.copyOf(caps), Map.copyOf(endpointCounts));
        // The local deltas are now included in the real DELIVERING counts.
        tenantPublished.clear();
        endpointPublished.clear();
    }

    private static Map<Long, Long> toMap(List<InFlight> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (InFlight row : rows) {
            if (row.id() != null) {
                map.put(row.id(), row.inFlight());
            }
        }
        return map;
    }

    /**
     * @return true when one more attempt for this (tenant, endpoint) fits the budget
     */
    public boolean admit(long tenantId, long endpointId) {
        Snapshot current = snapshot;

        TenantBudget budget = current.budgets().get(tenantId);
        if (budget != null && budget.maxInFlight() > 0) {
            long used = current.tenantInFlight().getOrDefault(tenantId, 0L) + delta(tenantPublished, tenantId);
            if (used >= budget.maxInFlight()) {
                return false;
            }
        }

        Integer cap = current.endpointCaps().get(endpointId);
        if (cap != null && cap > 0) {
            long used = current.endpointInFlight().getOrDefault(endpointId, 0L)
                    + delta(endpointPublished, endpointId);
            return used < cap;
        }
        return true;
    }

    /** Records that the scheduler just put one message for this pair on the wire. */
    public void markPublished(long tenantId, long endpointId) {
        tenantPublished.computeIfAbsent(tenantId, key -> new AtomicLong()).incrementAndGet();
        endpointPublished.computeIfAbsent(endpointId, key -> new AtomicLong()).incrementAndGet();
    }

    /** The tenants that must not be scheduled right now. */
    public Set<Long> blockedTenants() {
        Snapshot current = snapshot;
        Set<Long> blocked = new java.util.HashSet<>();
        current.budgets().forEach((tenantId, budget) -> {
            if (budget.maxInFlight() > 0) {
                long used = current.tenantInFlight().getOrDefault(tenantId, 0L)
                        + delta(tenantPublished, tenantId);
                if (used >= budget.maxInFlight()) {
                    blocked.add(tenantId);
                }
            }
        });
        return blocked;
    }

    /** The endpoints that must not be scheduled right now. */
    public Set<Long> blockedEndpoints() {
        Snapshot current = snapshot;
        Set<Long> blocked = new java.util.HashSet<>();
        current.endpointCaps().forEach((endpointId, cap) -> {
            if (cap != null && cap > 0) {
                long used = current.endpointInFlight().getOrDefault(endpointId, 0L)
                        + delta(endpointPublished, endpointId);
                if (used >= cap) {
                    blocked.add(endpointId);
                }
            }
        });
        return blocked;
    }

    /** Scheduling weight; tenants unknown to the last snapshot are weight 1. */
    public int weightOf(long tenantId) {
        TenantBudget budget = snapshot.budgets().get(tenantId);
        return budget == null ? 1 : Math.max(1, budget.weight());
    }

    private static long delta(Map<Long, AtomicLong> counters, long key) {
        AtomicLong counter = counters.get(key);
        return counter == null ? 0L : counter.get();
    }

    /** Logged at startup so an operator can see whether the fence is armed. */
    public void logState() {
        if (log.isInfoEnabled()) {
            log.info("Admission control refreshed: {} tenant budgets, {} endpoint caps, "
                            + "fairness enabled={}",
                    snapshot.budgets().size(), snapshot.endpointCaps().size(),
                    properties.fairness().enabled());
        }
    }
}
