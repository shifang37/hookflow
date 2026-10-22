package dev.hookflow.domain.event;

import java.time.LocalDateTime;

/**
 * An accepted event. {@code payload} stays a raw JSON string end to end so that
 * the platform never re-serialises customer data: what was accepted is exactly
 * what is delivered.
 */
public record Event(
        Long id,
        Long tenantId,
        String eventType,
        String payload,
        String idempotencyKey,
        LocalDateTime createdAt) {
}
