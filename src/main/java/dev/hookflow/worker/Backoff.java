package dev.hookflow.worker;

import java.util.SplittableRandom;

/**
 * Exponential backoff with equal jitter.
 *
 * <p>{@code base * 2^(attempt-1)} capped at {@code max}, then jittered into
 * {@code [delay/2, delay)}. The jitter matters more than the exponent: without
 * it, a broker outage makes every waiting delivery retry in the same millisecond
 * and the recovering endpoint is hit by a synchronised wave.
 */
public final class Backoff {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final SplittableRandom random;

    public Backoff(long baseDelayMs, long maxDelayMs) {
        this(baseDelayMs, maxDelayMs, new SplittableRandom());
    }

    public Backoff(long baseDelayMs, long maxDelayMs, SplittableRandom random) {
        if (baseDelayMs <= 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("require 0 < baseDelay <= maxDelay");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = random;
    }

    /** Delay before the given attempt number (1-based) is retried. */
    public long delayForAttempt(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
        long exponential = baseDelayMs;
        for (int i = 1; i < attemptNo && exponential < maxDelayMs; i++) {
            exponential = Math.min(exponential * 2, maxDelayMs);
        }
        long capped = Math.min(exponential, maxDelayMs);
        long half = capped / 2;
        if (half <= 0) {
            return capped;
        }
        return half + random.nextLong(half);
    }
}
