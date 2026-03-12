package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudradar.ingester.config.IngesterProperties;
import org.junit.jupiter.api.Test;

class OpenSkyRateLimitMetricCalculatorTest {

  @Test
  void computesCoreMetricsFromState() {
    IngesterProperties.RateLimit rateLimit = new IngesterProperties.RateLimit(4_000L, 50, 80, 95, 30_000L, 300_000L);
    long resetAt = (System.currentTimeMillis() / 1000L) + 5L;

    assertThat(metric(OpenSkyRateLimitTracker.Metric.REMAINING_CREDITS, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(350.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.CREDIT_LIMIT, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(400.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.REQUESTS_SINCE_RESET, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(10.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.CREDITS_USED_SINCE_RESET, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(50.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.EVENTS_SINCE_RESET, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(100.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.LAST_STATES_COUNT, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(25.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.CONSUMED_PERCENT, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(12.5);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.AVERAGE_CREDITS_PER_REQUEST, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(5.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.AVERAGE_EVENTS_PER_REQUEST, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(10.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.RESET_ETA_SECONDS, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isBetween(0.0, 6.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.QUOTA, 350L, 400L, 10L, 50L, 100L, 25L, resetAt, rateLimit))
        .isEqualTo(400.0);
  }

  @Test
  void appliesSafeFallbacksForInvalidOrMissingValues() {
    IngesterProperties.RateLimit rateLimit = new IngesterProperties.RateLimit(4_000L, 50, 80, 95, 30_000L, 300_000L);

    assertThat(metric(OpenSkyRateLimitTracker.Metric.CONSUMED_PERCENT, -1L, 400L, 0L, 0L, 0L, 0L, 0L, rateLimit))
        .isEqualTo(0.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.CONSUMED_PERCENT, 100L, 0L, 0L, 0L, 0L, 0L, 0L, rateLimit))
        .isEqualTo(0.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.AVERAGE_CREDITS_PER_REQUEST, 100L, 400L, 0L, 10L, 0L, 0L, 0L, rateLimit))
        .isEqualTo(0.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.AVERAGE_EVENTS_PER_REQUEST, 100L, 400L, 0L, 0L, 10L, 0L, 0L, rateLimit))
        .isEqualTo(0.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.RESET_ETA_SECONDS, 100L, 400L, 0L, 0L, 0L, 0L, 0L, rateLimit))
        .isEqualTo(0.0);

    assertThat(metric(OpenSkyRateLimitTracker.Metric.QUOTA, 100L, 0L, 0L, 0L, 0L, 0L, 0L, rateLimit))
        .isEqualTo(4_000.0);
    assertThat(metric(OpenSkyRateLimitTracker.Metric.QUOTA, 100L, 0L, 0L, 0L, 0L, 0L, 0L, null))
        .isEqualTo(0.0);
  }

  private double metric(
      OpenSkyRateLimitTracker.Metric metric,
      long remainingCredits,
      long creditLimitOverride,
      long requestsSinceReset,
      long creditsUsedSinceReset,
      long eventsSinceReset,
      long lastStatesCount,
      long resetAtEpochSeconds,
      IngesterProperties.RateLimit rateLimit) {
    return OpenSkyRateLimitMetricCalculator.value(
        metric,
        remainingCredits,
        creditLimitOverride,
        requestsSinceReset,
        creditsUsedSinceReset,
        eventsSinceReset,
        lastStatesCount,
        resetAtEpochSeconds,
        rateLimit);
  }
}
