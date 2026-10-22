package dev.hookflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All tunables in one place. Every value here has a documented default in
 * {@code application.yml}; nothing is hard-coded inside business logic.
 *
 * <p>Per-tenant policy (concurrency budget, weight) lives in the database, not
 * here: it is a property of the customer, and changing it must not require a
 * restart of the fleet. The values below are the fleet-wide switches around it.
 */
@ConfigurationProperties(prefix = "hookflow")
public record HookFlowProperties(
        Delivery delivery,
        Lease lease,
        Relay relay,
        Retry retry,
        Security security,
        RateLimit rateLimit,
        Admin admin,
        Fairness fairness,
        Backpressure backpressure,
        Worker worker) {

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
     * @param retentionDays  how long a PUBLISHED row is kept for debugging before
     *                       the table is pruned; without this the table grows forever
     * @param maxQueueLength broker-side cap on the delivery queue; 0 disables it.
     *                       With {@code reject-publish} overflow the broker refuses
     *                       the message, the relay puts the row back, and the
     *                       backlog stays in MySQL where it can be scheduled fairly
     */
    public record Relay(int batchSize,
                        int maxPublishAttempts,
                        int intervalMs,
                        int stalePublishingSeconds,
                        int retentionDays,
                        int maxQueueLength) {
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

    /**
     * Multi-tenant fair scheduling of the outbox -> broker edge.
     *
     * <p>Why the relay and not the consumer: a single FIFO queue cannot be made
     * fair by a consumer, because a small tenant's message is never even *handed*
     * to the consumer while 10 000 messages of a big tenant sit in front of it.
     * The only place where fairness is achievable without extra queues is the
     * decision "which outbox row becomes a broker message next". See
     * {@code docs/adr/0009-fair-scheduling.md}.
     *
     * @param enabled             false restores the v0.2 behaviour (publish in id
     *                            order, no admission control); used by FairnessIT
     *                            to measure the starvation ratio as a before/after
     * @param refreshIntervalMs   how often in-flight counts are re-read from MySQL;
     *                            shorter means tighter budgets but more counting
     */
    public record Fairness(boolean enabled, int refreshIntervalMs) {
    }

    /**
     * Load shedding at the front door.
     *
     * <p>The outbox is the real backlog, so it is also the honest place to decide
     * "we are full": refusing an event costs the caller one 429 and protects every
     * already-accepted event. Accepting it costs the platform its memory and MySQL.
     *
     * @param outboxHighWatermark platform-wide un-published backlogs above which
     *                            ingest is shed with 503; 0 disables the check
     * @param tenantHighWatermark per-tenant un-published backlog above which *that
     *                            tenant* is shed with 429; 0 disables the check
     * @param retryAfterSeconds   value of the {@code Retry-After} header on a shed
     * @param checkIntervalMs     how often the (cached) backlog counts are refreshed
     */
    public record Backpressure(long outboxHighWatermark,
                               long tenantHighWatermark,
                               int retryAfterSeconds,
                               int checkIntervalMs) {
    }

    /**
     * Worker-side reaction when a delivery cannot be started because its tenant
     * or endpoint is already at its concurrency budget.
     *
     * <p>The relay normally prevents this, but a stale in-flight count or a
     * disabled scheduler can still deliver an over-budget message. The three
     * policies have measurably different costs; see {@code docs/capacity.md}.
     *
     * @param overCapacityPolicy {@code defer} acknowledges now and lets
     *                           {@code DeliveryRecovery} re-enqueue at the cost of
     *                           one stuck-scan interval; {@code requeue} nacks for
     *                           immediate redelivery and burns CPU while saturated;
     *                           {@code drop} acknowledges and relies on the same
     *                           recovery path as {@code defer} but without the
     *                           attempt fence re-arm
     */
    public record Worker(String overCapacityPolicy) {
    }
}
