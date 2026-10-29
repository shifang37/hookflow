package dev.hookflow.sdk;

import java.util.List;
import java.util.Objects;

/**
 * The result of a batch submission, in request order.
 *
 * <p>The client sorts the server's items by {@code index} and verifies that every
 * requested index is present. A caller can therefore iterate
 * {@link #getResults()} and pair position {@code i} with the request item at
 * position {@code i}.
 */
public final class BatchResult {

    private final List<BatchItemResult> results;

    /**
     * Creates a batch result.
     *
     * @param results items sorted by their request index; must not be {@code null}
     */
    public BatchResult(List<BatchItemResult> results) {
        this.results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
    }

    /**
     * Returns the per-event results, ordered by request index.
     *
     * @return an immutable list with one item per submitted event
     */
    public List<BatchItemResult> getResults() {
        return results;
    }

    /**
     * Counts the items that did not create a new event.
     *
     * @return the number of items with {@code replay=true}
     */
    public int replayCount() {
        int count = 0;
        for (BatchItemResult result : results) {
            if (result.isReplay()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "BatchResult{results=" + results + '}';
    }
}
