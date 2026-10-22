package dev.hookflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

    /**
     * Outbox -> broker publishing.
     *
     * @param retentionDays how long a PUBLISHED row is kept for debugging before
     *                      the table is pruned; without this the table grows forever
     */
    public record Relay(int batchSize,
                        int maxPublishAttempts,
                        int intervalMs,
                        int stalePublishingSeconds,
                        int retentionDays) {
    }

    /** Exponential backoff with jitter. */
    public record Retry(int baseDelayMs, int maxDelayMs, int defaultMaxAttempts) {
    }

    /**
     * @param masterKey base64 encoded 32-byte AES key; injected via env var, never committed
     * @param keyVersion recorded next to each ciphertext so keys can be rotated
     * @param egress     outbound target policy (SSRF defence)
     */
    public record Security(String masterKey, short keyVersion, String signatureHeader, Egress egress) {
    }

    /**
     * Outbound target policy. Defaults are deliberately strict: a webhook platform
     * that will happily POST to {@code 169.254.169.254} is an SSRF pivot into the
     * host's cloud metadata service.
     *
     * @param allowPrivateTargets allow loopback / private / link-local targets;
     *                            only for local demos and tests
     * @param allowedHosts        explicit host allow-list that overrides the range
     *                            checks (exact match)
     */
    public record Egress(boolean allowPrivateTargets, List<String> allowedHosts) {
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
