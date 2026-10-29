package dev.hookflow.sdk;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry configuration for a {@link HookFlowClient}.
 *
 * <p>The defaults — three attempts, 200 ms base delay, 10 s cap, 20 % jitter — are
 * tuned for an ingestion call that a request thread is waiting on. The policy applies
 * only to failures that a retry can fix: transport errors, {@code 429} and {@code 5xx}.
 * Other {@code 4xx} responses fail immediately.
 *
 * <p>The delay after attempt {@code n} is {@code baseDelay * 2^(n-1)}, capped at
 * {@link #getMaxDelay()}, then spread by {@code ±jitter}. When the server sends
 * {@code Retry-After}, that value wins (also capped at {@code maxDelay}) unless
 * {@link #isHonorRetryAfter()} is disabled.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RetryPolicy {

    private static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(10);
    private static final double DEFAULT_JITTER = 0.2;

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitter;
    private final boolean honorRetryAfter;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.baseDelay = builder.baseDelay;
        this.maxDelay = builder.maxDelay;
        this.jitter = builder.jitter;
        this.honorRetryAfter = builder.honorRetryAfter;
    }

    /**
     * Returns the default policy: 3 attempts, 200 ms base delay, 10 s cap, 20 % jitter,
     * honouring {@code Retry-After}.
     *
     * @return the default policy
     */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /**
     * Starts building a custom policy.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the total number of attempts, including the first one.
     *
     * @return the maximum number of attempts (1 means "never retry")
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the delay before the first retry.
     *
     * @return the base backoff delay
     */
    public Duration getBaseDelay() {
        return baseDelay;
    }

    /**
     * Returns the upper bound for any single delay, including {@code Retry-After}.
     *
     * @return the maximum delay between attempts
     */
    public Duration getMaxDelay() {
        return maxDelay;
    }

    /**
     * Returns the relative jitter applied to exponential delays.
     *
     * @return a fraction in {@code [0, 1]}; {@code 0.2} means ±20 %
     */
    public double getJitter() {
        return jitter;
    }

    /**
     * Tells whether a server-provided {@code Retry-After} overrides the exponential delay.
     *
     * @return {@code true} when {@code Retry-After} is honoured
     */
    public boolean isHonorRetryAfter() {
        return honorRetryAfter;
    }

    /**
     * Builder for {@link RetryPolicy}.
     *
     * <p>Not thread-safe; build one policy per client.
     */
    public static final class Builder {

        private int maxAttempts = 3;
        private Duration baseDelay = DEFAULT_BASE_DELAY;
        private Duration maxDelay = DEFAULT_MAX_DELAY;
        private double jitter = DEFAULT_JITTER;
        private boolean honorRetryAfter = true;

        private Builder() {
        }

        /**
         * Sets the total number of attempts, including the first one.
         *
         * @param maxAttempts at least 1
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the delay before the first retry.
         *
         * @param baseDelay non-negative delay
         * @return this builder
         */
        public Builder baseDelay(Duration baseDelay) {
            this.baseDelay = Objects.requireNonNull(baseDelay, "baseDelay must not be null");
            return this;
        }

        /**
         * Sets the cap for any single delay.
         *
         * @param maxDelay non-negative delay
         * @return this builder
         */
        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay must not be null");
            return this;
        }

        /**
         * Sets the relative jitter.
         *
         * @param jitter a fraction in {@code [0, 1]}
         * @return this builder
         */
        public Builder jitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        /**
         * Controls whether {@code Retry-After} overrides the exponential delay.
         *
         * @param honorRetryAfter {@code true} to honour the header
         * @return this builder
         */
        public Builder honorRetryAfter(boolean honorRetryAfter) {
            this.honorRetryAfter = honorRetryAfter;
            return this;
        }

        /**
         * Validates the settings and builds the policy.
         *
         * @return an immutable retry policy
         * @throws IllegalArgumentException if any setting is out of range
         */
        public RetryPolicy build() {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            if (baseDelay.isNegative()) {
                throw new IllegalArgumentException("baseDelay must not be negative");
            }
            if (maxDelay.isNegative()) {
                throw new IllegalArgumentException("maxDelay must not be negative");
            }
            if (jitter < 0 || jitter > 1) {
                throw new IllegalArgumentException("jitter must be between 0 and 1");
            }
            return new RetryPolicy(this);
        }
    }
}
