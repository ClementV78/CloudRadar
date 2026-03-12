package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudradar.ingester.config.IngesterProperties;
import org.junit.jupiter.api.Test;

class OpenSkyRateLimitTrackerTest {

  @Test
  void usesHeaderLimitForDelayCalculationWhenAvailable() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildProperties(4_000L));

    tracker.recordFetch(0);
    tracker.recordSuccessfulCycle(399, 400, null);

    assertThat(tracker.currentDelayMs()).isEqualTo(10_000L);
    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.CREDIT_LIMIT)).isEqualTo(400.0);
  }

  @Test
  void fallsBackToConfiguredQuotaWhenHeaderLimitMissing() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildProperties(4_000L));

    tracker.recordFetch(0);
    tracker.recordSuccessfulCycle(399, null, null);

    assertThat(tracker.currentDelayMs()).isEqualTo(30_000L);
    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.CREDIT_LIMIT)).isEqualTo(4_000.0);
  }

  @Test
  void storesResetEpochWhenHeaderPresent() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildProperties(4_000L));
    long futureReset = (System.currentTimeMillis() / 1000L) + 300L;

    tracker.recordFetch(0);
    tracker.recordSuccessfulCycle(390, 400, futureReset);

    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.RESET_ETA_SECONDS)).isGreaterThan(0.0);
  }

  @Test
  void keepsBaseDelayWhenRateLimitConfigMissing() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildPropertiesWithoutRateLimit());

    tracker.recordFetch(0);
    tracker.recordSuccessfulCycle(399, 400, null);

    assertThat(tracker.currentDelayMs()).isEqualTo(10_000L);
  }

  @Test
  void keepsBaseDelayWhenRemainingCreditsMissing() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildProperties(4_000L));

    tracker.recordFetch(0);
    tracker.recordSuccessfulCycle(null, null, null);

    assertThat(tracker.currentDelayMs()).isEqualTo(10_000L);
  }

  @Test
  void resetsPeriodCountersWhenCreditsIncrease() {
    OpenSkyRateLimitTracker tracker = new OpenSkyRateLimitTracker(buildProperties(4_000L));

    tracker.recordFetch(10);
    tracker.recordSuccessfulCycle(300, 4_000, null);
    tracker.recordFetch(5);
    tracker.recordSuccessfulCycle(350, 4_000, null);

    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.REQUESTS_SINCE_RESET)).isEqualTo(0.0);
    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.CREDITS_USED_SINCE_RESET)).isEqualTo(0.0);
    assertThat(tracker.metric(OpenSkyRateLimitTracker.Metric.EVENTS_SINCE_RESET)).isEqualTo(0.0);
  }

  private IngesterProperties buildProperties(long quota) {
    return new IngesterProperties(
        10_000L,
        new IngesterProperties.Redis("cloudradar:ingest:queue"),
        new IngesterProperties.Bbox(46.0, 50.0, 2.0, 4.0),
        new IngesterProperties.RateLimit(quota, 50, 80, 95, 30_000L, 300_000L),
        new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.0));
  }

  private IngesterProperties buildPropertiesWithoutRateLimit() {
    return new IngesterProperties(
        10_000L,
        new IngesterProperties.Redis("cloudradar:ingest:queue"),
        new IngesterProperties.Bbox(46.0, 50.0, 2.0, 4.0),
        null,
        new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.0));
  }
}
