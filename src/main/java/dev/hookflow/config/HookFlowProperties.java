package dev.hookflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables in one place. Every value here has a documented default in
 * {@code application.yml}; nothing is hard-coded inside business logic.
 */
@ConfigurationProperties(prefix = "hookflow")
public record HookFlowProperties(
        Delivery delivery,
        Lease lease,
        Relay relay,
        Retry retry,
        Security security,
        RateLimit rateLimit,
        Admin admin) {

    /** Outbound HTTP behaviour towards customer endpoints. */
    public record Delivery(
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxResponsePreviewBytes,
            int maxPayloadBytes) {
    }

    /**
     * A worker holds a time-boxed lease while performing one attempt. The lease
     * is the fence that stops a zombie worker from overwriting a newer one.
     */
    public record Lease(int ttlSeconds, int sweepIntervalMs) {
    }

    /** Outbox -> broker publishing. */
    public record Relay(int batchSize, int maxPublishAttempts, int intervalMs, int stalePublishingSeconds) {
    }

    /** Exponential backoff with jitter. */
    public record Retry(int baseDelayMs, int maxDelayMs, int defaultMaxAttempts) {
    }

    /**
     * @param masterKey base64 encoded 32-byte AES key; injected via env var, never committed
     * @param keyVersion recorded next to each ciphertext so keys can be rotated
     */
    public record Security(String masterKey, short keyVersion, String signatureHeader) {
    }

    /**
     * @param failOpen when Redis is down: true keeps accepting events, false rejects them
     */
    public record RateLimit(boolean enabled, boolean failOpen) {
    }

    /**
     * @param bootstrapToken shared secret that guards tenant provisioning; it is
     *                       an operator credential, not a tenant credential
     */
    public record Admin(String bootstrapToken) {
    }
}
