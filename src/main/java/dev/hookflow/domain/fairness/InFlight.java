package dev.hookflow.domain.fairness;

/**
 * One row of a {@code GROUP BY} over the delivery table: "how many deliveries of
 * this tenant (or endpoint) are DELIVERING right now".
 */
public record InFlight(Long id, long inFlight) {
}
