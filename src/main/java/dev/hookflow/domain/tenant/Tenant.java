package dev.hookflow.domain.tenant;

import java.time.LocalDateTime;

/** A tenant is the unit of isolation: quota, api keys, endpoints and events all hang off it. */
public record Tenant(
        Long id,
        String name,
        int rateLimit,
        int maxInFlight,
        int weight,
        String status,
        LocalDateTime createdAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** {@code 0} means the tenant has no concurrency budget of its own. */
    public boolean hasConcurrencyBudget() {
        return maxInFlight > 0;
    }
}
