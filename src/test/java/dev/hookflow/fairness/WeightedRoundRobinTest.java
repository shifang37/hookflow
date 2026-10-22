package dev.hookflow.fairness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selector is the part of fairness that can be proven without a database:
 * over any window, the share must match the weight, and the gap between two turns
 * of the lowest-weight key must stay bounded.
 *
 * <p>The second property is the interesting one. A selector can produce a perfect
 * 30:10 split while making the weight-1 tenant wait 30 turns in a row — that is
 * still starvation, just averaged away.
 */
class WeightedRoundRobinTest {

    private final WeightedRoundRobin<String> scheduler = new WeightedRoundRobin<>();

    @Test
    @DisplayName("equal weights alternate instead of running in blocks")
    void equalWeightsAlternate() {
        List<String> order = pick(List.of("a", "b"), Map.of("a", 1, "b", 1), 6);
        assertThat(order).containsExactly("a", "b", "a", "b", "a", "b");
    }

    @Test
    @DisplayName("weights 3:1 produce 30/10 over 40 turns")
    void weightsAreProportional() {
        Map<String, Integer> weights = Map.of("a", 3, "b", 1);
        List<String> order = pick(List.of("a", "b"), weights, 40);

        assertThat(order.stream().filter("a"::equals).count()).isEqualTo(30);
        assertThat(order.stream().filter("b"::equals).count()).isEqualTo(10);
    }

    @Test
    @DisplayName("the light tenant's gap is bounded by the weight ratio, not by the queue")
    void lowWeightIsNeverStarved() {
        Map<String, Integer> weights = Map.of("a", 3, "b", 1);
        List<String> order = pick(List.of("a", "b"), weights, 40);

        int longestGap = longestGap(order, "b");
        assertThat(longestGap)
                .as("a gap proportional to the queue would be starvation; "
                        + "a gap bounded by the configured ratio is fairness")
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a heavier share never widens the gap with the queue depth")
    void gapDoesNotGrowWithTheNumberOfTurns() {
        Map<String, Integer> weights = Map.of("a", 3, "b", 1);
        List<String> order = pick(List.of("a", "b"), weights, 400);

        assertThat(longestGap(order, "b"))
                .as("serving 10x more work must not make the small tenant wait longer")
                .isLessThanOrEqualTo(3);
        assertThat(order.stream().filter("b"::equals).count()).isEqualTo(100);
    }

    @Test
    @DisplayName("a very heavy tenant still cannot lock a light one out")
    void extremeWeightRatioStaysBounded() {
        Map<String, Integer> weights = Map.of("heavy", 100, "light", 1);
        List<String> order = pick(List.of("heavy", "light"), weights, 200);

        assertThat(longestGap(order, "light")).isLessThanOrEqualTo(100);
        assertThat(order.stream().filter("light"::equals).count())
                .as("the light tenant does get its turn")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("an empty candidate set selects nothing")
    void emptyCandidates() {
        assertThat(scheduler.next(List.of(), key -> 1)).isNull();
    }

    @Test
    @DisplayName("a weight below 1 is treated as 1, never as zero or negative")
    void degenerateWeightsBehaveAsOne() {
        List<String> order = pick(List.of("a", "b"), Map.of("a", 0, "b", -5), 4);
        assertThat(order).containsExactly("a", "b", "a", "b");
    }

    @Test
    @DisplayName("forgetting a key drops its phase but keeps the rest")
    void forgetResetsPhase() {
        scheduler.next(List.of("a", "b"), key -> 1);
        scheduler.next(List.of("a", "b"), key -> 1);
        assertThat(scheduler.tracked()).isEqualTo(2);
        scheduler.forget("a");
        assertThat(scheduler.tracked()).isEqualTo(1);
    }

    private List<String> pick(List<String> candidates, Map<String, Integer> weights, int turns) {
        List<String> order = new ArrayList<>(turns);
        for (int i = 0; i < turns; i++) {
            String key = scheduler.next(candidates, k -> weights.getOrDefault(k, 1));
            if (key == null) {
                break;
            }
            order.add(key);
        }
        return order;
    }

    private static int longestGap(List<String> order, String key) {
        int longest = 0;
        int current = 0;
        for (String element : order) {
            if (element.equals(key)) {
                longest = Math.max(longest, current);
                current = 0;
            } else {
                current++;
            }
        }
        return longest;
    }
}
