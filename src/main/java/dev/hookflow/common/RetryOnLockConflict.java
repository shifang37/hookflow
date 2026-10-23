package dev.hookflow.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Re-runs a transaction that lost a lock conflict.
 *
 * <p><b>Why this exists.</b> The same pattern was hand-written twice — in
 * {@code EndpointService.pause} and in {@code DemoController.reset} — and both
 * copies were found by stress, not by review. A deadlock is InnoDB's normal
 * scheduler behaviour, not a logic error: a bulk {@code UPDATE} by a dimension
 * (endpoint, tenant) and a worker's primary-key CAS touch the same rows in
 * opposite order, and the engine picks one of them as the victim. The only answer
 * available to the application layer is to replay the transaction; the third call
 * site would otherwise be written without it. See
 * {@code docs/failures/0007-cancel-vs-claim-deadlock.md}.
 *
 * <p><b>What is retried and what is not.</b> Only
 * {@link PessimisticLockingFailureException} — that covers both
 * {@code DeadlockLoserDataAccessException} (victim of a deadlock) and
 * {@code CannotAcquireLockException} (lock wait timeout), which are the two ways
 * InnoDB says "somebody else had it first". Everything else is a real failure and
 * is never retried here.
 *
 * <p><b>What the caller owes.</b> The action must be <em>idempotent</em>: it will
 * run again from the start, with nothing carried over from the failed attempt (the
 * transaction was rolled back). All current users are: pausing an already paused
 * endpoint is a no-op, and the demo reset deletes rows it has already deleted.
 *
 * <p>The retry is deliberately bounded and short (3 attempts, 20/40 ms). A lock
 * conflict that survives that is not transient any more, and making the caller wait
 * longer would only move the failure to the HTTP timeout. Every conflict is counted
 * in {@code hookflow.db.lock_conflict{operation}} so the deadlock rate is visible
 * instead of being hidden by successful retries.
 */
@Component
public class RetryOnLockConflict {

    private static final Logger log = LoggerFactory.getLogger(RetryOnLockConflict.class);

    /** Total attempts, including the first one. */
    public static final int DEFAULT_ATTEMPTS = 3;

    /**
     * Linear backoff: 20 ms after the first conflict, 40 ms after the second. Long
     * enough for the competing transaction to commit, short enough that three
     * attempts stay inside a normal HTTP budget.
     */
    private static final long BASE_BACKOFF_MS = 20L;

    private final MeterRegistry meterRegistry;
    private final int maxAttempts;

    @Autowired
    public RetryOnLockConflict(MeterRegistry meterRegistry) {
        this(meterRegistry, DEFAULT_ATTEMPTS);
    }

    /** Visible for tests that need a smaller attempt budget. */
    RetryOnLockConflict(MeterRegistry meterRegistry, int maxAttempts) {
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param operation metric tag / log label; use {@code endpoint.pause} style
     * @param action    a whole transaction, not a single statement — retrying a
     *                  statement is the wrong unit, because the deadlock victim is
     *                  picked per transaction
     * @throws PessimisticLockingFailureException when the conflict survives every attempt
     */
    public <T> T execute(String operation, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException lockConflict) {
                meterRegistry.counter("hookflow.db.lock_conflict", "operation", operation).increment();
                if (attempt >= maxAttempts) {
                    log.error("{} still conflicts after {} attempts; giving up", operation, attempt);
                    throw lockConflict;
                }
                long backoffMs = BASE_BACKOFF_MS * attempt;
                log.warn("{} lost a lock conflict (attempt {}/{}); retrying in {} ms",
                        operation, attempt, maxAttempts, backoffMs);
                if (!sleepQuietly(backoffMs)) {
                    // An interrupted caller (shutdown, cancelled request) must fail
                    // fast, and the useful exception is the conflict itself.
                    Thread.currentThread().interrupt();
                    throw lockConflict;
                }
            }
        }
    }

    /** Same contract as {@link #execute(String, Supplier)} for actions with no result. */
    public void executeWithoutResult(String operation, Runnable action) {
        execute(operation, () -> {
            action.run();
            return null;
        });
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
