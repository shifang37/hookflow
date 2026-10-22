package dev.hookflow.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backoff is small enough to test exhaustively, and it is the one place where
 * a wrong constant turns into a retry storm in production.
 */
class BackoffTest {

    @Test
    @DisplayName("delay doubles per attempt and is always inside [cap/2, cap)")
    void delayGrowsExponentiallyAndStaysWithinJitterBounds() {
        long base = 100;
        long max = 10_000;
        Backoff backoff = new Backoff(base, max);

        long[] expectedCaps = {100, 200, 400, 800, 1_600, 3_200, 6_400, 10_000, 10_000};
        for (int attempt = 1; attempt <= expectedCaps.length; attempt++) {
            long cap = expectedCaps[attempt - 1];
            for (int sample = 0; sample < 200; sample++) {
                long delay = backoff.delayForAttempt(attempt);
                assertThat(delay)
                        .as("attempt %d", attempt)
                        .isBetween(cap / 2, cap - 1);
            }
        }
    }

    @Test
    @DisplayName("jitter actually varies: a fixed exponent must not produce a fixed delay")
    void jitterProducesVariation() {
        Backoff backoff = new Backoff(1_000, 60_000);
        long first = backoff.delayForAttempt(4);
        boolean sawDifferentValue = false;
        for (int i = 0; i < 50 && !sawDifferentValue; i++) {
            sawDifferentValue = backoff.delayForAttempt(4) != first;
        }
        assertThat(sawDifferentValue)
                .as("without variation every retry would fire in the same millisecond")
                .isTrue();
    }

    @Test
    @DisplayName("never exceeds the configured maximum")
    void neverExceedsMaximum() {
        Backoff backoff = new Backoff(5_000, 60_000);
        for (int attempt = 1; attempt <= 64; attempt++) {
            assertThat(backoff.delayForAttempt(attempt)).isLessThan(60_000);
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new Backoff(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(100, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(100, 200).delayForAttempt(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
