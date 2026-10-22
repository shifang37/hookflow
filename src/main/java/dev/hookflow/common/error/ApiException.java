package dev.hookflow.common.error;

/** Business failure that maps to a documented {@link ErrorCode}. */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    /** {@code Retry-After} value; only load-shedding responses carry one. */
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, String message, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
