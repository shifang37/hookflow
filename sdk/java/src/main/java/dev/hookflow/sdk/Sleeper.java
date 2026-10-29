package dev.hookflow.sdk;

import java.time.Duration;

/**
 * Blocking wait used between retry attempts.
 *
 * <p>It exists so tests can record delays instead of actually waiting, and so an
 * application that already has a scheduler does not have to let the client own a
 * thread. The default is {@link #system()}.
 *
 * <p>Implementations must throw {@link InterruptedException} rather than swallow it:
 * the client restores the thread's interrupt flag and aborts the retry loop.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Waits for the requested duration.
     *
     * @param duration how long to wait; never negative
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void sleep(Duration duration) throws InterruptedException;

    /**
     * Returns the default sleeper, backed by {@link Thread#sleep(long)}.
     *
     * @return a sleeper that blocks the calling thread
     */
    static Sleeper system() {
        return duration -> {
            long millis = duration.toMillis();
            if (millis > 0) {
                Thread.sleep(millis);
            }
        };
    }
}
