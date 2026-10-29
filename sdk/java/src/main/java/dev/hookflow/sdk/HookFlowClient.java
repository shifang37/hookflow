package dev.hookflow.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Official Java client for the HookFlow event ingestion API.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * try (HookFlowClient client = HookFlowClient.builder()
 *         .apiKey("hk_...")
 *         .baseUrl("https://hookflow.example.com")
 *         .build()) {
 *     EventResult result = client.sendEvent("order.paid", Map.of("orderId", 12345));
 *     System.out.println(result.getEventId());
 * }
 * }</pre>
 *
 * <h2>Idempotency</h2>
 * <p>Every request carries an {@code Idempotency-Key}. When the caller does not pass
 * one, {@link EventRequest} generates a UUID at construction time and the client
 * reuses both that key and the serialised request body for every retry. A retry can
 * therefore never create a duplicate event.
 *
 * <h2>Retries</h2>
 * <p>Only failures a retry can fix are retried: transport errors, {@code 429} and
 * {@code 5xx}. Other {@code 4xx} responses throw immediately. Delays follow
 * {@link RetryPolicy}; {@code Retry-After} wins when present. With the default
 * policy an unsuccessful {@code sendEvent} can take up to
 * {@code 3 * callTimeout + 2 * maxDelay} — about 80 seconds with the default
 * 20-second call timeout and 10-second delay cap. Do not call it from a request
 * thread with a shorter budget.
 *
 * <h2>Thread safety</h2>
 * <p>The client is immutable. The shared {@link OkHttpClient} is thread-safe, so one
 * client can serve an application's whole event-ingestion path. {@link #close()}
 * shuts down the dispatcher, so create one client per application, not per event.
 *
 * @see HookFlowWebhook
 * @see EventRequest
 */
public final class HookFlowClient implements AutoCloseable {

    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final String SDK_VERSION = "0.1.0";

    /** Mirrors the server default {@code hookflow.delivery.max-payload-bytes}. */
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private static final int MAX_BATCH_SIZE = 500;

    private final String apiKey;
    private final String baseUrl;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final OkHttpClient httpClient;

    private HookFlowClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
        this.retryPolicy = builder.retryPolicy;
        this.sleeper = builder.sleeper;
        // OkHttp retries some connection failures on its own, which would make the
        // attempt count (and therefore the delay math) invisible to the caller.
        // Retrying is this client's job.
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(builder.connectTimeout)
                .readTimeout(builder.readTimeout)
                .callTimeout(builder.callTimeout)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Starts building a client.
     *
     * @return a new builder with default timeouts and {@link RetryPolicy#defaults()}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Publishes one event with an automatically generated idempotency key.
     *
     * @param eventType event type, matching {@code [A-Za-z0-9._-]} (1-128 characters)
     * @param payload   any JSON-serialisable value; must not be {@code null}
     * @return the server's result
     * @throws IllegalArgumentException if the arguments fail client-side validation
     * @throws HookFlowException        if the server rejects the event or all attempts fail
     */
    public EventResult sendEvent(String eventType, Object payload) {
        return sendEvent(new EventRequest(eventType, payload));
    }

    /**
     * Publishes one event with an explicit idempotency key.
     *
     * @param eventType      event type, matching {@code [A-Za-z0-9._-]} (1-128 characters)
     * @param payload        any JSON-serialisable value; must not be {@code null}
     * @param idempotencyKey 1–255 character key, reused by every retry
     * @return the server's result
     * @throws IllegalArgumentException if the arguments fail client-side validation
     * @throws HookFlowException        if the server rejects the event or all attempts fail
     */
    public EventResult sendEvent(String eventType, Object payload, String idempotencyKey) {
        return sendEvent(new EventRequest(eventType, payload, idempotencyKey));
    }

    /**
     * Publishes one event.
     *
     * <p>The request's payload is serialised once, before the first attempt. Every
     * retry resends exactly those bytes and the same idempotency key, which is what
     * makes a retry safe: the server can tell a replay from a different payload.
     *
     * @param request the event to publish
     * @return the server's result
     * @throws HookFlowException if the server rejects the event or all attempts fail
     */
    public EventResult sendEvent(EventRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        byte[] payloadJson = serializePayload(request.getPayload());
        byte[] body = singleRequestBody(request.getEventType(), payloadJson);

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/events")
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", request.getIdempotencyKey())
                .header("User-Agent", "hookflow-java-sdk/" + SDK_VERSION)
                .header("Accept", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = executeWithRetry(httpRequest)) {
            if (response.isSuccessful()) {
                return parseEventResult(response);
            }
            throw toException(response);
        }
    }

    /**
     * Publishes a batch of events in one transaction.
     *
     * <p>The whole batch is serialised once and resent unchanged on retries; each
     * item keeps its own idempotency key. The returned list is sorted by
     * {@code index} and lines up one-to-one with {@code requests}.
     *
     * @param requests 1–500 events
     * @return one result per event, in request order
     * @throws IllegalArgumentException if the list is empty, larger than 500, or an
     *                                  item fails validation
     * @throws HookFlowException        if the server rejects the batch or all attempts fail
     */
    public BatchResult sendEvents(List<EventRequest> requests) {
        Objects.requireNonNull(requests, "requests must not be null");
        if (requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "requests must contain between 1 and " + MAX_BATCH_SIZE + " events but had " + requests.size());
        }

        // Serialise every payload before the retry loop starts. The same byte array is
        // reused for every attempt, so a retry is byte-identical to the first request.
        List<byte[]> payloads = new ArrayList<>(requests.size());
        for (EventRequest request : requests) {
            Objects.requireNonNull(request, "requests must not contain null entries");
            payloads.add(serializePayload(request.getPayload()));
        }
        byte[] body = batchRequestBody(requests, payloads);

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/events/batch")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "hookflow-java-sdk/" + SDK_VERSION)
                .header("Accept", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = executeWithRetry(httpRequest)) {
            if (response.isSuccessful()) {
                return parseBatchResult(response, requests.size());
            }
            throw toException(response);
        }
    }

    /**
     * Shuts down the client's HTTP dispatcher and connection pool.
     *
     * <p>In-flight calls are interrupted by the dispatcher shutdown; this method does
     * not wait for them. After closing, the client must not be used again.
     */
    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (IOException ignored) {
                // Nothing useful can be done while closing; the pool is already evicted.
            }
        }
    }

    // ------------------------------------------------------------------
    // Retry loop
    // ------------------------------------------------------------------

    /**
     * Runs the request, retrying only the failures that a retry can fix.
     *
     * <p>The returned response is open and owned by the caller; retried responses are
     * closed here so their connections return to the pool.
     */
    private Response executeWithRetry(Request request) {
        int attempt = 1;
        while (true) {
            try {
                Response response = httpClient.newCall(request).execute();
                if (attempt >= retryPolicy.getMaxAttempts() || !isRetryableStatus(response.code())) {
                    return response;
                }
                Integer retryAfter = parseRetryAfter(response.header("Retry-After"));
                // Consume the (small) error body before closing so the connection can be
                // reused cleanly for the next attempt.
                drain(response);
                response.close();
                pauseBeforeRetry(attempt, retryAfter);
                attempt++;
            } catch (IOException e) {
                if (attempt >= retryPolicy.getMaxAttempts()) {
                    throw new HookFlowException(0, 0, null, null,
                            "network error after " + attempt + " attempt(s): " + e.getMessage(), null, e);
                }
                pauseBeforeRetry(attempt, null);
                attempt++;
            }
        }
    }

    private void pauseBeforeRetry(int failedAttempt, Integer retryAfterSeconds) {
        Duration delay = delayFor(failedAttempt, retryAfterSeconds);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HookFlowException(0, 0, null, null,
                    "interrupted while waiting to retry", null, e);
        }
    }

    Duration delayFor(int failedAttempt, Integer retryAfterSeconds) {
        long capMillis = retryPolicy.getMaxDelay().toMillis();
        if (retryAfterSeconds != null && retryPolicy.isHonorRetryAfter()) {
            long requested = Math.max(0L, retryAfterSeconds) * 1000L;
            return Duration.ofMillis(Math.min(requested, capMillis));
        }

        long delayMillis = retryPolicy.getBaseDelay().toMillis();
        for (int i = 1; i < failedAttempt && delayMillis < capMillis; i++) {
            delayMillis = Math.min(delayMillis * 2, capMillis);
        }
        delayMillis = Math.min(delayMillis, capMillis);

        double jitter = retryPolicy.getJitter();
        if (jitter > 0) {
            double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
            delayMillis = Math.round(delayMillis * factor);
        }
        return Duration.ofMillis(Math.max(1L, Math.min(delayMillis, Math.max(1L, capMillis))));
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * Reads and discards a response body so its connection can be reused.
     *
     * <p>Failure to read is irrelevant here: the response is being thrown away
     * either way, and the caller only needs the retry decision.
     */
    private static void drain(Response response) {
        try {
            ResponseBody body = response.body();
            if (body != null) {
                body.bytes();
            }
        } catch (IOException ignored) {
            // The connection is closed; the next attempt opens a fresh one.
        }
    }

    private static Integer parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            // The server sends whole seconds. An HTTP-date is not part of the
            // contract, so fall back to exponential backoff instead of guessing.
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Request bodies
    // ------------------------------------------------------------------

    private static byte[] serializePayload(Object payload) {
        final byte[] bytes;
        try {
            bytes = Json.MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalArgumentException("payload is not JSON-serialisable", e);
        }
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "payload is " + bytes.length + " bytes; the server limit is " + MAX_PAYLOAD_BYTES);
        }
        return bytes;
    }

    private static byte[] singleRequestBody(String eventType, byte[] payloadJson) {
        String body = "{\"eventType\":" + Json.quote(eventType)
                + ",\"payload\":" + new String(payloadJson, StandardCharsets.UTF_8) + '}';
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] batchRequestBody(List<EventRequest> requests, List<byte[]> payloads) {
        StringBuilder body = new StringBuilder("{\"events\":[");
        for (int i = 0; i < requests.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            EventRequest request = requests.get(i);
            body.append("{\"eventType\":").append(Json.quote(request.getEventType()))
                    .append(",\"payload\":").append(new String(payloads.get(i), StandardCharsets.UTF_8))
                    .append(",\"idempotencyKey\":").append(Json.quote(request.getIdempotencyKey()))
                    .append('}');
        }
        return body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    private static EventResult parseEventResult(Response response) {
        byte[] body = readBody(response);
        try {
            return Json.MAPPER.readValue(body, EventResult.class);
        } catch (IOException e) {
            throw malformedResponse(response, "malformed event response: " + e.getMessage());
        }
    }

    private BatchResult parseBatchResult(Response response, int expectedSize) {
        byte[] body = readBody(response);
        final List<BatchItemResult> items;
        try {
            items = Json.MAPPER.readValue(body, new TypeReference<List<BatchItemResult>>() {
            });
        } catch (IOException e) {
            throw malformedResponse(response, "malformed batch response: " + e.getMessage());
        }
        if (items == null || items.size() != expectedSize) {
            throw malformedResponse(response, "batch response has "
                    + (items == null ? 0 : items.size()) + " results for " + expectedSize + " events");
        }

        List<BatchItemResult> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(BatchItemResult::getIndex));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getIndex() != i) {
                throw malformedResponse(response,
                        "batch response is missing index " + i + " or repeats an index");
            }
        }
        return new BatchResult(sorted);
    }

    private static byte[] readBody(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } catch (IOException e) {
            throw new HookFlowException(response.code(), 0, null, response.header("X-Request-Id"),
                    "unable to read response body: " + e.getMessage(), null, e);
        }
    }

    private static HookFlowException malformedResponse(Response response, String detail) {
        return new HookFlowException(response.code(), 0, null, response.header("X-Request-Id"),
                detail, null, null);
    }

    /**
     * Turns a non-2xx response into the SDK's exception without losing the request id.
     */
    private static HookFlowException toException(Response response) {
        int status = response.code();
        String requestId = response.header("X-Request-Id");
        Integer retryAfter = parseRetryAfter(response.header("Retry-After"));
        String bodyText = "";
        try {
            bodyText = new String(readBody(response), StandardCharsets.UTF_8);
        } catch (HookFlowException ignored) {
            // The status line and headers are still worth reporting.
        }

        int code = 0;
        String errorName = null;
        String serverMessage = null;
        try {
            JsonNode node = Json.MAPPER.readTree(bodyText);
            if (node != null && node.isObject()) {
                code = node.path("code").asInt(0);
                if (node.hasNonNull("error")) {
                    errorName = node.get("error").asText();
                }
                if (node.hasNonNull("message")) {
                    serverMessage = node.get("message").asText();
                }
                if (node.hasNonNull("requestId")) {
                    requestId = node.get("requestId").asText();
                }
            }
        } catch (IOException ignored) {
            // Not the documented error shape; the raw body goes into the message below.
        }

        StringBuilder message = new StringBuilder("HTTP ").append(status);
        if (errorName != null) {
            message.append(' ').append(errorName);
        }
        String detail = serverMessage != null ? serverMessage : bodyText;
        if (!detail.isBlank()) {
            message.append(": ").append(detail);
        }
        if (requestId != null) {
            message.append(" (requestId=").append(requestId).append(')');
        }

        return new HookFlowException(status, code, errorName, requestId,
                message.toString(), retryAfter, null);
    }

    /**
     * Builder for {@link HookFlowClient}.
     *
     * <p>{@code apiKey} and a valid {@code baseUrl} are required; everything else has
     * a default. The builder is mutable, but the client it produces is not.
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = "http://localhost:8080";
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(15);
        private Duration callTimeout = Duration.ofSeconds(20);
        private Sleeper sleeper = Sleeper.system();

        private Builder() {
        }

        /**
         * Sets the tenant API key sent as {@code Authorization: Bearer ...}.
         *
         * @param apiKey a non-blank key; HookFlow issues keys starting with {@code hk_}
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the API base URL.
         *
         * @param baseUrl an absolute {@code http} or {@code https} URL, for example
         *                {@code https://hookflow.example.com}
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the retry policy.
         *
         * @param retryPolicy the policy to use; {@link RetryPolicy#defaults()} otherwise
         * @return this builder
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        /**
         * Sets the TCP connect timeout.
         *
         * @param connectTimeout a positive duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
            return this;
        }

        /**
         * Sets the socket read timeout.
         *
         * @param readTimeout a positive duration
         * @return this builder
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout must not be null");
            return this;
        }

        /**
         * Sets the timeout for one whole attempt.
         *
         * <p>This bounds one HTTP attempt, not the retried call. Multiply by the retry
         * policy's attempt count when sizing a caller's own timeout.
         *
         * @param callTimeout a positive duration
         * @return this builder
         */
        public Builder callTimeout(Duration callTimeout) {
            this.callTimeout = Objects.requireNonNull(callTimeout, "callTimeout must not be null");
            return this;
        }

        /**
         * Sets the sleeper used between attempts.
         *
         * <p>The default blocks the calling thread. Tests and applications with their
         * own scheduler can supply another implementation.
         *
         * @param sleeper the sleeper to use
         * @return this builder
         */
        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
            return this;
        }

        /**
         * Validates the settings and creates the client.
         *
         * @return a new client
         * @throws IllegalArgumentException if the API key is missing, the base URL is
         *                                  not an absolute http(s) URL, or a timeout is
         *                                  not positive
         */
        public HookFlowClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            HttpUrl parsed = HttpUrl.parse(baseUrl);
            if (parsed == null || !("http".equals(parsed.scheme()) || "https".equals(parsed.scheme()))) {
                throw new IllegalArgumentException(
                        "baseUrl must be an absolute http(s) URL but was \"" + baseUrl + "\"");
            }
            requirePositive(connectTimeout, "connectTimeout");
            requirePositive(readTimeout, "readTimeout");
            requirePositive(callTimeout, "callTimeout");
            return new HookFlowClient(this);
        }

        private static void requirePositive(Duration value, String name) {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
