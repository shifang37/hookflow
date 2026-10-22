package dev.hookflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, documented error codes. The numeric code is part of the public API
 * contract: clients switch on {@code code}, never on the human message.
 */
public enum ErrorCode {

    INVALID_REQUEST(40000, HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "Missing or invalid API key"),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "API key lacks the required scope"),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(40500, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    PAYLOAD_TOO_LARGE(41300, HttpStatus.CONTENT_TOO_LARGE, "Payload exceeds the size limit"),
    UNSUPPORTED_MEDIA_TYPE(41500, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    IDEMPOTENCY_KEY_REUSED(40901, HttpStatus.CONFLICT, "Idempotency key already used with a different payload"),
    ENDPOINT_PAUSED(40902, HttpStatus.CONFLICT, "Endpoint is paused"),
    ILLEGAL_TRANSITION(40903, HttpStatus.CONFLICT, "Delivery is not in a state that allows this operation"),
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS, "Tenant rate limit exceeded"),
    /**
     * This tenant's own un-published backlog is above its watermark, so accepting
     * more would only make its queue longer. Only this tenant is affected.
     */
    TENANT_BACKLOG_EXCEEDED(42902, HttpStatus.TOO_MANY_REQUESTS, "Tenant backlog limit exceeded"),
    INTERNAL(50000, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    DEPENDENCY_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE, "Dependent service is unavailable"),
    /**
     * The platform as a whole is out of capacity. Shedding here is what keeps
     * already-accepted events deliverable; the alternative is an out-of-memory
     * stop that loses nothing but stalls everything.
     */
    OVER_CAPACITY(50301, HttpStatus.SERVICE_UNAVAILABLE, "Service is shedding load, retry later");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
