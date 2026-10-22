package dev.hookflow.worker;

import java.util.SplittableRandom;

/**
 * Exponential backoff with equal jitter.
 *
 * <p>{@code base * 2^(attempt-1)} capped at {@code max}, then jittered into
 * {@code [cap/2, cap)}. The jitter matters more than the exponent: without it, a
 * broker outage makes every waiting delivery retry in the same millisecond and the
 * recovering endpoint is hit by a synchronised wave.
 *
 * <p>{@link #capForAttempt(int)} exposes the un-jittered bound so the jitter
 * invariant can be stated as a property instead of a hand-picked example.
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

    /** The un-jittered upper bound before the given attempt number (1-based). */
    public long capForAttempt(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
        long exponential = baseDelayMs;
        for (int i = 1; i < attemptNo && exponential < maxDelayMs; i++) {
            exponential = Math.min(exponential * 2, maxDelayMs);
        }
        return Math.min(exponential, maxDelayMs);
    }

    /** Delay before the given attempt number (1-based) is retried. */
    public long delayForAttempt(int attemptNo) {
        long capped = capForAttempt(attemptNo);
        long half = capped / 2;
        if (half <= 0) {
            return capped;
        }
        return half + random.nextLong(half);
    }
}
