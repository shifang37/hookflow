package dev.hookflow.fairness;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Smooth weighted round robin (the nginx / LVS algorithm).
 *
 * <p>Why not plain round robin: a tenant that pays for a bigger share must get
 * more turns, and a tenant with weight 1 must still get a turn in <em>every</em>
 * window — that is the whole point. Plain round robin ignores weight; a plain
 * "serve the tenant with the largest backlog first" starves the small tenant
 * completely. SWRR gives both: proportional share and a bounded gap between two
 * consecutive turns of the same tenant.
 *
 * <p>That last property is the one that matters here. For weights 3:1 the order is
 * {@code A B A A A B ...}: {@code B} never waits for more than three turns, and —
 * crucially — that number depends on the <em>weight ratio</em>, never on how much
 * work {@code A} has queued. A backlog of a million events does not widen the gap;
 * a 100:1 weight does, which is a business decision rather than a queuing accident.
 *
 * <p>The state is a per-key credit map, so the caller controls the candidate
 * set on every call and the algorithm keeps its history across calls. Callers
 * that drop a key (a tenant with no work) must call {@link #forget} to keep the
 * credit map from growing without bound; forgetting a key loses nothing but its
 * current phase.
 */
public final class WeightedRoundRobin<K> {

    private final Map<K, Long> credit = new java.util.HashMap<>();

    /**
     * @param candidates keys with pending work, in any stable order
     * @param weight     weight of a key; values below 1 are treated as 1
     * @return the key that should be served next, or {@code null} when there is
     *         nothing to serve
     */
    public K next(Iterable<? extends K> candidates, ToIntFunction<K> weight) {
        K best = null;
        long bestCredit = Long.MIN_VALUE;
        long totalWeight = 0;
        for (K candidate : candidates) {
            int w = Math.max(1, weight.applyAsInt(candidate));
            totalWeight += w;
            long current = credit.merge(candidate, (long) w, Long::sum);
            // Strict '>' keeps ties alternating instead of always favouring the
            // first candidate in iteration order.
            if (current > bestCredit) {
                bestCredit = current;
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        credit.merge(best, -totalWeight, Long::sum);
        return best;
    }

    /** Drops a key's credit; call it when the key has nothing left to serve. */
    public void forget(K key) {
        credit.remove(key);
    }

    public int tracked() {
        return credit.size();
    }
}
