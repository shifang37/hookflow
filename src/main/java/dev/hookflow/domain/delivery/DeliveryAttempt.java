package dev.hookflow.domain.delivery;

import java.time.LocalDateTime;

/**
 * The record of one network attempt. Written as {@code IN_PROGRESS} before the
 * HTTP call and finalised after it, so a crashed worker leaves a visible trace
 * instead of a silent gap.
 */
public record DeliveryAttempt(
        Long id,
        Long deliveryId,
        int attemptNo,
        String status,
        Integer statusCode,
        String responsePreview,
        String errorClass,
        Integer durationMs,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String ABANDONED = "ABANDONED";
}
