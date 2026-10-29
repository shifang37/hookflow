package dev.hookflow.domain.endpoint;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A customer callback target.
 *
 * <p>{@code secretCipher} is the AES-GCM encrypted signing secret; plaintext is
 * only materialised in memory at signing time.
 */
public record Endpoint(
        Long id,
        Long tenantId,
        String url,
        byte[] secretCipher,
        short secretKeyVersion,
        String subscribedTypes,
        int maxAttempts,
        int maxConcurrency,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";

    /**
     * Soft-deleted. The row stays because {@code delivery.endpoint_id} references it;
     * every API path that takes an endpoint id treats it as missing, and fan-out never
     * returns it. The generated {@code url_key} column releases the URL for re-creation
     * — see {@code V5__endpoint_soft_delete.sql}.
     */
    public static final String DELETED = "DELETED";

    public static final String WILDCARD = "*";

    /** {@code 0} means "no endpoint-level cap; the tenant budget is the only fence". */
    public boolean hasConcurrencyCap() {
        return maxConcurrency > 0;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public boolean isDeleted() {
        return DELETED.equals(status);
    }

    /** Returns true when this endpoint should receive the given event type. */
    public boolean subscribesTo(String eventType) {
        if (subscribedTypes == null || subscribedTypes.isBlank()) {
            return false;
        }
        Set<String> types = Arrays.stream(subscribedTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return types.contains(WILDCARD) || types.contains(eventType);
    }
}
