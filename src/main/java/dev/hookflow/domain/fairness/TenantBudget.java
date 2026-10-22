package dev.hookflow.domain.fairness;

/**
 * The per-tenant scheduling budget.
 *
 * @param id          tenant id
 * @param maxInFlight how many of this tenant's deliveries may be DELIVERING at
 *                    the same time; {@code 0} means "no cap"
 * @param weight      relative share of admission turns while tenants contend
 */
public record TenantBudget(Long id, int maxInFlight, int weight) {
}
