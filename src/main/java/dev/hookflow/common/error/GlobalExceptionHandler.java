package dev.hookflow.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** Maps every exception that reaches MVC onto the documented error body. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e, HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (e.retryAfterSeconds() != null) {
            // A shed request must tell the caller *when* to come back; without this
            // header every client retries immediately and turns shedding into a
            // self-inflicted retry storm.
            builder.header("Retry-After", Integer.toString(e.retryAfterSeconds()));
        }
        return builder.body(ApiError.of(code, e.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(detail.isEmpty() ? "Request validation failed" : detail, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return badRequest("Missing required header: " + e.getHeaderName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return badRequest("Request body is not valid JSON", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(code.status()).body(ApiError.of(code, e.getMessage(), requestId(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException e,
                                                    HttpServletRequest request) {
        ErrorCode code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, "Content-Type must be application/json", requestId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, "No such endpoint", requestId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return badRequest(e.getMessage() == null ? "Invalid argument" : e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        // Log the detail, return a request id. Never leak internals to the caller.
        log.error("Unhandled exception for request {}", requestId(request), e);
        ErrorCode code = ErrorCode.INTERNAL;
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, code.defaultMessage(), requestId(request)));
    }

    private ResponseEntity<ApiError> badRequest(String message, HttpServletRequest request) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(code, message, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return dev.hookflow.common.web.RequestIdFilter.currentRequestId(request);
    }
}
