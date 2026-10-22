package dev.hookflow.worker;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based coverage of the retry ladder.
 *
 * <p>Example-based tests pick a handful of attempts and constants; the failures
 * that matter here live in the boundary combinations (attempt 1, cap that is not a
 * power of two, max smaller than the natural growth). These properties run over
 * hundreds of generated triples instead.
 */
class BackoffPropertyTest {

    @Property
    void delayAlwaysLandsInTheJitterBand(
            @ForAll("configs") Config config,
            @ForAll @IntRange(min = 1, max = 40) int attemptNo) {

        Backoff backoff = new Backoff(config.base(), config.max());
        long cap = backoff.capForAttempt(attemptNo);
        long delay = backoff.delayForAttempt(attemptNo);

        assertThat(delay).isBetween(cap / 2, cap);
        assertThat(delay).isPositive();
    }

    @Property
    void capNeverExceedsTheConfiguredMaximum(
            @ForAll("configs") Config config,
            @ForAll @IntRange(min = 1, max = 200) int attemptNo) {

        assertThat(new Backoff(config.base(), config.max()).capForAttempt(attemptNo))
                .isLessThanOrEqualTo(config.max());
    }

    @Property
    void capIsMonotonicInAttemptNumber(
            @ForAll("configs") Config config,
            @ForAll @IntRange(min = 1, max = 100) int attemptNo) {

        Backoff backoff = new Backoff(config.base(), config.max());
        assertThat(backoff.capForAttempt(attemptNo + 1))
                .isGreaterThanOrEqualTo(backoff.capForAttempt(attemptNo));
    }

    @Property
    void firstAttemptUsesTheBaseDelay(
            @ForAll("configs") Config config) {
        assertThat(new Backoff(config.base(), config.max()).capForAttempt(1)).isEqualTo(config.base());
    }

    /** base <= max, both positive, including max == base and non-power-of-two caps. */
    @Provide
    Arbitrary<Config> configs() {
        return Arbitraries.integers().between(1, 20_000).flatMap(base ->
                Arbitraries.integers().between(base, 3_600_000).map(max -> new Config(base, max)));
    }

    record Config(long base, long max) {
    }
}
