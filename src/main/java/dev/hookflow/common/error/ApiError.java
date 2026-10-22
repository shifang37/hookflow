package dev.hookflow.common.error;

import java.time.Instant;

/**
 * The single error body shape for every non-2xx response.
 *
 * <p>{@code message} is always safe to display: it never contains secrets,
 * stack traces, or raw customer payloads.
 */
public record ApiError(
        int code,
        String error,
        String message,
        String requestId,
        Instant timestamp) {

    public static ApiError of(ErrorCode errorCode, String message, String requestId) {
        return new ApiError(errorCode.code(), errorCode.name(), message, requestId, Instant.now());
    }
}
