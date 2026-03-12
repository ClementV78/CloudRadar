package com.cloudradar.ingester;

import com.cloudradar.ingester.config.IngesterProperties;
import java.util.EnumMap;

final class OpenSkyRateLimitMetricCalculator {
  private interface MetricReader {
    double read(State state);
  }

  private record State(
      long remainingCredits,
      long creditLimitOverride,
      long requestsSinceReset,
      long creditsUsedSinceReset,
      long eventsSinceReset,
      long lastStatesCount,
      long resetAtEpochSeconds,
      IngesterProperties.RateLimit rateLimit) {}

  private static final EnumMap<OpenSkyRateLimitTracker.Metric, MetricReader> READERS =
      new EnumMap<>(OpenSkyRateLimitTracker.Metric.class);

  static {
    READERS.put(OpenSkyRateLimitTracker.Metric.REMAINING_CREDITS, State::remainingCredits);
    READERS.put(OpenSkyRateLimitTracker.Metric.CREDIT_LIMIT, State::creditLimitOverride);
    READERS.put(OpenSkyRateLimitTracker.Metric.REQUESTS_SINCE_RESET, State::requestsSinceReset);
    READERS.put(OpenSkyRateLimitTracker.Metric.CREDITS_USED_SINCE_RESET, State::creditsUsedSinceReset);
    READERS.put(OpenSkyRateLimitTracker.Metric.CONSUMED_PERCENT, state -> consumedPercent(
        state.remainingCredits(),
        state.creditLimitOverride()));
    READERS.put(OpenSkyRateLimitTracker.Metric.EVENTS_SINCE_RESET, State::eventsSinceReset);
    READERS.put(OpenSkyRateLimitTracker.Metric.LAST_STATES_COUNT, State::lastStatesCount);
    READERS.put(OpenSkyRateLimitTracker.Metric.AVERAGE_CREDITS_PER_REQUEST, state -> average(
        state.creditsUsedSinceReset(),
        state.requestsSinceReset()));
    READERS.put(OpenSkyRateLimitTracker.Metric.AVERAGE_EVENTS_PER_REQUEST, state -> average(
        state.eventsSinceReset(),
        state.requestsSinceReset()));
    READERS.put(OpenSkyRateLimitTracker.Metric.RESET_ETA_SECONDS, state -> resetEtaSeconds(state.resetAtEpochSeconds()));
    READERS.put(OpenSkyRateLimitTracker.Metric.QUOTA, state -> effectiveQuota(
        state.rateLimit(),
        state.creditLimitOverride()));
  }

  private OpenSkyRateLimitMetricCalculator() {}

  static double value(
      OpenSkyRateLimitTracker.Metric metric,
      long remainingCredits,
      long creditLimitOverride,
      long requestsSinceReset,
      long creditsUsedSinceReset,
      long eventsSinceReset,
      long lastStatesCount,
      long resetAtEpochSeconds,
      IngesterProperties.RateLimit rateLimit) {
    MetricReader reader = READERS.get(metric);
    if (reader == null) {
      return 0.0;
    }
    return reader.read(new State(
        remainingCredits,
        creditLimitOverride,
        requestsSinceReset,
        creditsUsedSinceReset,
        eventsSinceReset,
        lastStatesCount,
        resetAtEpochSeconds,
        rateLimit));
  }

  private static double consumedPercent(long remainingCredits, long creditLimitOverride) {
    if (creditLimitOverride <= 0 || remainingCredits < 0) {
      return 0.0;
    }
    long used = Math.max(0L, creditLimitOverride - remainingCredits);
    double consumed = ((double) used / (double) creditLimitOverride) * 100.0;
    if (consumed < 0.0) {
      return 0.0;
    }
    if (consumed > 100.0) {
      return 100.0;
    }
    return consumed;
  }

  private static double average(long total, long count) {
    if (count == 0L) {
      return 0.0;
    }
    return (double) total / (double) count;
  }

  private static double resetEtaSeconds(long resetAtEpochSeconds) {
    if (resetAtEpochSeconds <= 0) {
      return 0.0;
    }
    long now = System.currentTimeMillis() / 1000L;
    return Math.max(0.0, (double) (resetAtEpochSeconds - now));
  }

  private static long effectiveQuota(IngesterProperties.RateLimit rateLimit, long creditLimitOverride) {
    if (creditLimitOverride > 0) {
      return creditLimitOverride;
    }
    if (rateLimit == null || rateLimit.quota() <= 0) {
      return 0L;
    }
    return rateLimit.quota();
  }
}
