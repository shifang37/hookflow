package dev.hookflow.sdk;

/**
 * The single exception type thrown for failed submissions.
 *
 * <p>Two shapes exist, deliberately:
 * <ul>
 *   <li>the server answered with an error: {@link #getStatusCode()} is the HTTP
 *       status, {@link #getCode()} and {@link #getErrorName()} come from the error
 *       body, and {@link #getRequestId()} is the identifier to quote to support;</li>
 *   <li>the request never got an answer (connect failure, read timeout, interrupt):
 *       {@link #getStatusCode()} is {@code 0} and {@link #getCause()} is the
 *       underlying {@code IOException}.</li>
 * </ul>
 *
 * <p>Validation errors detected before any request is sent are
 * {@link IllegalArgumentException}, not this type: they are programming errors and
 * retrying them cannot help.
 *
 * <p>{@link #isRetryable()} describes whether the client <em>would</em> have retried
 * the failure; once an exception reaches the caller the client has already exhausted
 * its attempts. It is useful to decide whether to retry at a higher level (for
 * example, the next scheduled job).
 */
public class HookFlowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final int code;
    private final String errorName;
    private final String requestId;
    private final Integer retryAfterSeconds;

    /**
     * Creates an exception.
     *
     * @param statusCode        HTTP status, or {@code 0} when no response was received
     * @param code              numeric error code from the server, or {@code 0}
     * @param errorName         symbolic error name from the server, or {@code null}
     * @param requestId         server request id, or {@code null}
     * @param message           human-readable message
     * @param retryAfterSeconds {@code Retry-After} from the last response, or {@code null}
     * @param cause             underlying failure, or {@code null}
     */
    public HookFlowException(int statusCode, int code, String errorName, String requestId,
                             String message, Integer retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
        this.errorName = errorName;
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the response status, or {@code 0} when the request never reached the server
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the server's numeric error code.
     *
     * @return the error code from the response body, or {@code 0} when absent or unparseable
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the server's symbolic error name.
     *
     * @return the {@code error} field from the response body (for example
     *         {@code IDEMPOTENCY_KEY_REUSED}), or {@code null}
     */
    public String getErrorName() {
        return errorName;
    }

    /**
     * Returns the request id, the one identifier support needs.
     *
     * @return the {@code requestId} from the error body or the {@code X-Request-Id}
     *         response header, or {@code null} for transport failures
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the server-requested retry delay, when it supplied one.
     *
     * @return the parsed {@code Retry-After} in seconds, or {@code null}
     */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Tells whether this failure class is worth retrying.
     *
     * @return {@code true} for transport failures, {@code 429} and {@code 5xx};
     *         {@code false} for other {@code 4xx} responses
     */
    public boolean isRetryable() {
        return statusCode == 0 || statusCode == 429 || statusCode >= 500;
    }

    @Override
    public String toString() {
        return "HookFlowException{statusCode=" + statusCode
                + ", code=" + code
                + ", errorName='" + errorName + '\''
                + ", requestId='" + requestId + '\''
                + ", message='" + getMessage() + '\'' + '}';
    }
}
