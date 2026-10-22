package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.crypto.HmacSigner;
import dev.hookflow.crypto.SecretCipher;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Performs one HTTP attempt against a customer endpoint.
 *
 * <p>Classification is the whole point of this class:
 * <ul>
 *   <li>2xx — success.</li>
 *   <li>408 / 429 / 5xx / connect error / read timeout — transient, retry.</li>
 *   <li>any other 4xx, or a 3xx we did not follow — permanent, do not retry.
 *       Retrying a 400 eight times only delays the inevitable and wastes
 *       capacity that other tenants could use.</li>
 * </ul>
 *
 * <p>The request body is assembled by hand around the stored payload string, which
 * is embedded verbatim: the payload was normalised once at ingest and is never
 * re-serialised on the delivery path. That keeps the hot path free of any JSON
 * library and makes the delivered bytes identical across every retry of the same
 * event — which is what a receiver's signature check and its event-id
 * deduplication rely on.
 */
@Component
public class HttpDeliverer {

    private final HttpClient httpClient;
    private final HookFlowProperties properties;
    private final SecretCipher secretCipher;

    public HttpDeliverer(HookFlowProperties properties, SecretCipher secretCipher) {
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.delivery().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record Outcome(
            boolean success,
            boolean retryable,
            Integer statusCode,
            String errorClass,
            String responsePreview,
            int durationMs) {
    }

    public Outcome deliver(Endpoint endpoint, Event event, Delivery delivery, int attemptNo) {
        byte[] secret = secretCipher.decrypt(endpoint.secretCipher());
        byte[] body = buildBody(event, delivery, attemptNo).getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = HmacSigner.sign(secret, event.id(), delivery.id(), timestamp, body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .timeout(Duration.ofMillis(properties.delivery().readTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "HookFlow/0.1")
                .header("X-HookFlow-Signature", signature)
                .header("X-HookFlow-Timestamp", Long.toString(timestamp))
                .header("X-HookFlow-Event-Id", Long.toString(event.id()))
                .header("X-HookFlow-Delivery-Id", Long.toString(delivery.id()))
                .header("X-HookFlow-Event-Type", event.eventType())
                .header("X-HookFlow-Attempt", Integer.toString(attemptNo))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        long startNanos = System.nanoTime();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int durationMs = elapsedMs(startNanos);
            return classify(response.statusCode(), response.body(), durationMs);
        } catch (HttpTimeoutException e) {
            return new Outcome(false, true, null, "TIMEOUT", truncate(e.getMessage()), elapsedMs(startNanos));
        } catch (IOException e) {
            return new Outcome(false, true, null, "CONNECT_ERROR", truncate(e.getMessage()), elapsedMs(startNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, true, null, "INTERRUPTED", truncate(e.getMessage()), elapsedMs(startNanos));
        } catch (IllegalArgumentException e) {
            // Malformed URI that slipped past validation: permanent.
            return new Outcome(false, false, null, "INVALID_URL", truncate(e.getMessage()), elapsedMs(startNanos));
        }
    }

    private Outcome classify(int statusCode, byte[] responseBody, int durationMs) {
        String preview = truncate(new String(responseBody, StandardCharsets.UTF_8));
        if (statusCode >= 200 && statusCode < 300) {
            return new Outcome(true, false, statusCode, null, preview, durationMs);
        }
        if (statusCode == 408 || statusCode == 429) {
            return new Outcome(false, true, statusCode, "RETRYABLE_4XX", preview, durationMs);
        }
        if (statusCode >= 500) {
            return new Outcome(false, true, statusCode, "SERVER_5XX", preview, durationMs);
        }
        if (statusCode >= 300 && statusCode < 400) {
            return new Outcome(false, false, statusCode, "CLIENT_3XX", preview, durationMs);
        }
        return new Outcome(false, false, statusCode, "CLIENT_4XX", preview, durationMs);
    }

    /**
     * Envelope around the untouched payload. {@code eventType} is validated to
     * {@code [A-Za-z0-9._-]} at the API boundary, so no escaping is required.
     */
    String buildBody(Event event, Delivery delivery, int attemptNo) {
        String occurredAt = event.createdAt() == null
                ? Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
                : event.createdAt().toInstant(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
        return "{"
                + "\"event_id\":" + event.id() + ","
                + "\"event_type\":\"" + event.eventType() + "\","
                + "\"occurred_at\":\"" + occurredAt + "\","
                + "\"delivery\":{\"id\":" + delivery.id() + ",\"attempt\":" + attemptNo + "},"
                + "\"payload\":" + event.payload()
                + "}";
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        int max = properties.delivery().maxResponsePreviewBytes();
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
