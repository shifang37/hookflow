package dev.hookflow.domain.outbox;

import java.time.LocalDateTime;

/**
 * A durable "publish this delivery to the broker" intention, written in the same
 * transaction as the business rows. The relay turns rows into messages; because
 * the relay can crash between "broker confirmed" and "row marked published", the
 * pipeline is at-least-once by construction.
 */
public record OutboxMessage(
        Long id,
        String aggregateType,
        Long aggregateId,
        int attemptNo,
        String payload,
        String status,
        LocalDateTime publishingSince,
        int publishAttempts,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {

    public static final String AGGREGATE_DELIVERY = "DELIVERY";
}
