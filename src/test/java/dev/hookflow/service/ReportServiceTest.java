package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The presentation policy of the read APIs, tested without a database.
 *
 * <p>These are the parts a wrong answer would be invisible in: a silently ignored
 * {@code range} or a success rate that counts in-flight deliveries as failures both
 * look plausible on a dashboard. The SQL behind them is covered by
 * {@code ReadApiIT}.
 */
class ReportServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"24h", "7d", "30d", "90d"})
    @DisplayName("every advertised range maps to the expected number of hours")
    void acceptedRangesMapToHours(String range) {
        int hours = ReportService.hoursOf(range);
        assertThat(hours).isPositive();
        assertThat(ReportService.normalizeRange(range)).isEqualTo(range);
    }

    @ParameterizedTest
    @CsvSource({"24h,24", "7d,168", "30d,720", "90d,2160"})
    void rangeToHoursExactly(String range, int expectedHours) {
        assertThat(ReportService.hoursOf(range)).isEqualTo(expectedHours);
    }

    @Test
    @DisplayName("a missing range defaults to 7d instead of throwing")
    void missingRangeDefaults() {
        assertThat(ReportService.normalizeRange(null)).isEqualTo("7d");
        assertThat(ReportService.normalizeRange("")).isEqualTo("7d");
        assertThat(ReportService.normalizeRange("   ")).isEqualTo("7d");
    }

    @Test
    @DisplayName("range is case- and whitespace-insensitive")
    void rangeIsNormalised() {
        assertThat(ReportService.normalizeRange(" 7D ")).isEqualTo("7d");
        assertThat(ReportService.hoursOf(ReportService.normalizeRange("30D"))).isEqualTo(720);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1h", "12h", "1y", "-7d", "7", "weekly"})
    @DisplayName("an unknown range is a 400, not a silently wrong window")
    void unknownRangeIsRejected(String range) {
        assertThatThrownBy(() -> ReportService.hoursOf(range))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("success rate counts settled deliveries only")
    void successRateIgnoresInFlight() {
        // 99 succeeded, 1 dead: the 1000 still-queued deliveries must not dilute this,
        // because an unfinished delivery is neither a success nor a failure yet.
        assertThat(ReportService.successRate(99, 1)).isEqualTo(99.0);
        assertThat(ReportService.successRate(1, 0)).isEqualTo(100.0);
        assertThat(ReportService.successRate(0, 1)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("success rate rounds to two decimals and never divides by zero")
    void successRateEdgeCases() {
        assertThat(ReportService.successRate(0, 0)).isZero();
        assertThat(ReportService.successRate(1, 2)).isEqualTo(33.33);
        assertThat(ReportService.successRate(2, 1)).isEqualTo(66.67);
        assertThat(ReportService.successRate(1, 3)).isEqualTo(25.0);
    }

    // ----------------------------------------------------------- chart buckets

    @ParameterizedTest
    @CsvSource({"24h,15m", "7d,3h", "30d,6h", "90d,1d"})
    @DisplayName("auto picks the finest interval that keeps the chart readable")
    void autoIntervalFitsTheWindow(String range, String expectedInterval) {
        int hours = ReportService.hoursOf(range);
        assertThat(ReportService.autoInterval(hours)).isEqualTo(expectedInterval);
    }

    @ParameterizedTest
    @ValueSource(strings = {"24h", "7d", "30d", "90d"})
    @DisplayName("auto never exceeds the point budget")
    void autoIntervalStaysUnderBudget(String range) {
        int hours = ReportService.hoursOf(range);
        int points = (int) (hours * 3600L / ReportService.intervalSeconds(
                ReportService.autoInterval(hours)));
        assertThat(points).isLessThanOrEqualTo(ReportService.MAX_POINTS).isPositive();
    }

    @Test
    @DisplayName("an explicit interval is honoured verbatim; auto is resolved")
    void resolveIntervalHonoursExplicitChoice() {
        assertThat(ReportService.resolveInterval("1h", 24)).isEqualTo("1h");
        assertThat(ReportService.resolveInterval(" 15M ", 24)).isEqualTo("15m");
        assertThat(ReportService.resolveInterval("auto", 168)).isEqualTo("3h");
        assertThat(ReportService.resolveInterval(null, 24)).isEqualTo("15m");
        assertThat(ReportService.resolveInterval("", 24)).isEqualTo("15m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2m", "45s", "1w", "1M", "hourly"})
    @DisplayName("an unknown interval is a 400 rather than a silent default")
    void unknownIntervalIsRejected(String interval) {
        assertThatThrownBy(() -> ReportService.resolveInterval(interval, 24))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> ReportService.intervalSeconds(interval))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @CsvSource({"5m,300", "15m,900", "30m,1800", "1h,3600", "3h,10800",
            "6h,21600", "12h,43200", "1d,86400"})
    void intervalSecondsAreExact(String interval, int expectedSeconds) {
        assertThat(ReportService.intervalSeconds(interval)).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName("the finest offered interval still fits the shortest range")
    void finestIntervalFitsShortestRange() {
        // 24h at 5m is 288 points; a 1m option would be 1440 and could never pass the
        // bucket guard, which is why it is not offered.
        assertThat(ReportService.expectedBuckets(24, ReportService.intervalSeconds("5m")))
                .isLessThanOrEqualTo(1000);
        assertThatThrownBy(() -> ReportService.intervalSeconds("1m"))
                .as("1m is deliberately not an option")
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @CsvSource({"24,3600,24", "168,10800,56", "24,900,96", "720,21600,120", "2160,86400,90"})
    @DisplayName("the point count is the range expressed in whole buckets, rounded up")
    void expectedBucketCount(int hours, int bucketSeconds, int expected) {
        assertThat(ReportService.expectedBuckets(hours, bucketSeconds)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a range that is not a whole number of buckets still gets a full point")
    void expectedBucketCountRoundsUp() {
        // 90 minutes of 1-hour buckets is two points, not one: the trailing half hour
        // must still be visible on the chart.
        assertThat(ReportService.expectedBuckets(1, 3600)).isEqualTo(1);
        assertThat(ReportService.expectedBuckets(2, 3600)).isEqualTo(2);
    }
}
