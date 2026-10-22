package dev.hookflow.domain.fairness;

/**
 * A per-endpoint concurrency cap. Only endpoints that actually declare one are
 * returned by the query, so an absent entry means "no endpoint-level cap".
 */
public record EndpointCap(Long id, int maxConcurrency) {
}
