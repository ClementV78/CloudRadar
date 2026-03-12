package com.cloudradar.ingester;

import com.cloudradar.ingester.config.IngesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OpenSkyRateLimitTracker {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyRateLimitTracker.class);

  enum Metric {
    REMAINING_CREDITS,
    CREDIT_LIMIT,
    REQUESTS_SINCE_RESET,
    CREDITS_USED_SINCE_RESET,
    CONSUMED_PERCENT,
    EVENTS_SINCE_RESET,
    LAST_STATES_COUNT,
    AVERAGE_CREDITS_PER_REQUEST,
    AVERAGE_EVENTS_PER_REQUEST,
    RESET_ETA_SECONDS,
    QUOTA
  }

  enum Threshold {
    WARN50,
    WARN80,
    WARN95
  }

  private enum RateLimitLevel {
    NORMAL,
    WARN_50,
    WARN_80,
    WARN_95
  }

  private final IngesterProperties properties;
  private long currentDelayMs;
  private RateLimitLevel lastRateLevel = RateLimitLevel.NORMAL;
  private long remainingCredits = -1L;
  private long lastRemainingCredits = -1L;
  private long creditLimitOverride = -1L;
  private long resetAtEpochSeconds = -1L;
  private long requestsSinceReset = 0L;
  private long creditsUsedSinceReset = 0L;
  private long eventsSinceReset = 0L;
  private long lastStatesCount = 0L;

  OpenSkyRateLimitTracker(IngesterProperties properties) {
    this.properties = properties;
    this.currentDelayMs = properties.refreshMs();
  }

  synchronized void recordFetch(int statesCount) {
    requestsSinceReset++;
    eventsSinceReset += statesCount;
    lastStatesCount = statesCount;
  }

  synchronized void recordSuccessfulCycle(Integer remaining, Integer creditLimit, Long resetAt) {
    updateCreditTracking(remaining);
    applyHeaders(creditLimit, resetAt);
    updateRateLimit(remaining);
  }

  synchronized long currentDelayMs() {
    return currentDelayMs;
  }

  synchronized double metric(Metric metric) {
    return OpenSkyRateLimitMetricCalculator.value(
        metric,
        remainingCredits,
        creditLimitOverride,
        requestsSinceReset,
        creditsUsedSinceReset,
        eventsSinceReset,
        lastStatesCount,
        resetAtEpochSeconds,
        properties.rateLimit());
  }

  synchronized double thresholdPercent(Threshold threshold) {
    IngesterProperties.RateLimit rateLimit = properties.rateLimit();
    if (rateLimit == null) {
      return 0.0;
    }
    return switch (threshold) {
      case WARN50 -> rateLimit.warn50();
      case WARN80 -> rateLimit.warn80();
      case WARN95 -> rateLimit.warn95();
    };
  }

  private void updateCreditTracking(Integer remaining) {
    if (remaining == null) {
      return;
    }

    long previous = lastRemainingCredits;
    lastRemainingCredits = remaining;
    remainingCredits = remaining;

    if (previous < 0) {
      return;
    }

    if (remaining > previous) {
      requestsSinceReset = 0L;
      creditsUsedSinceReset = 0L;
      eventsSinceReset = 0L;
      resetAtEpochSeconds = -1L;
      return;
    }

    long delta = previous - remaining;
    if (delta > 0) {
      creditsUsedSinceReset += delta;
    }
  }

  private void applyHeaders(Integer creditLimit, Long resetAt) {
    if (creditLimit != null && creditLimit > 0) {
      applyCreditLimitFromHeader(creditLimit);
    } else {
      IngesterProperties.RateLimit configured = properties.rateLimit();
      if (configured != null && configured.quota() > 0) {
        creditLimitOverride = configured.quota();
      }
    }

    if (resetAt != null && resetAt > 0) {
      long previous = resetAtEpochSeconds;
      resetAtEpochSeconds = resetAt;
      if (previous != resetAt) {
        log.info("OpenSky rate-limit reset header updated: reset_at_epoch_seconds={}", resetAt);
      }
    }
  }

  private void applyCreditLimitFromHeader(long creditLimit) {
    long previous = creditLimitOverride;
    creditLimitOverride = creditLimit;
    if (previous == creditLimit) {
      return;
    }

    IngesterProperties.RateLimit configured = properties.rateLimit();
    long configuredQuota = configured == null ? 0L : Math.max(0L, configured.quota());
    log.info(
        "OpenSky rate-limit header detected: limit={} (configured quota={})",
        creditLimit,
        configuredQuota);
    if (configuredQuota > 0 && configuredQuota != creditLimit) {
      log.warn(
          "OpenSky header limit ({}) differs from configured OPENSKY_CREDITS_QUOTA ({}). Throttling now uses header limit.",
          creditLimit,
          configuredQuota);
    }
  }

  private void updateRateLimit(Integer remaining) {
    IngesterProperties.RateLimit rateLimit = properties.rateLimit();
    if (rateLimit == null || remaining == null) {
      return;
    }

    long quota = effectiveQuota(rateLimit);
    if (quota <= 0) {
      return;
    }

    double consumed = ((double) (quota - remaining) / (double) quota) * 100.0;
    consumed = clampPercent(consumed);

    RateLimitLevel level = resolveLevel(consumed, rateLimit);
    if (level != lastRateLevel) {
      log.warn(
          "OpenSky credits consumed {}% (remaining: {}/{}). Threshold reached: {}%",
          String.format("%.1f", consumed),
          remaining,
          quota,
          thresholdPercentForLevel(level, rateLimit));
      lastRateLevel = level;
    }

    long newDelayMs = level == RateLimitLevel.WARN_95
        ? rateLimit.refreshCriticalMs()
        : (level == RateLimitLevel.WARN_80 ? rateLimit.refreshWarnMs() : properties.refreshMs());
    if (newDelayMs != currentDelayMs) {
      log.info("Adjusting refresh interval to {}s based on OpenSky credit usage", newDelayMs / 1000L);
      currentDelayMs = newDelayMs;
    }
  }

  private long effectiveQuota(IngesterProperties.RateLimit rateLimit) {
    if (creditLimitOverride > 0) {
      return creditLimitOverride;
    }
    if (rateLimit == null || rateLimit.quota() <= 0) {
      return 0L;
    }
    return rateLimit.quota();
  }

  private RateLimitLevel resolveLevel(double consumed, IngesterProperties.RateLimit rateLimit) {
    if (consumed >= rateLimit.warn95()) {
      return RateLimitLevel.WARN_95;
    }
    if (consumed >= rateLimit.warn80()) {
      return RateLimitLevel.WARN_80;
    }
    if (consumed >= rateLimit.warn50()) {
      return RateLimitLevel.WARN_50;
    }
    return RateLimitLevel.NORMAL;
  }

  private int thresholdPercentForLevel(RateLimitLevel level, IngesterProperties.RateLimit rateLimit) {
    return switch (level) {
      case WARN_95 -> rateLimit.warn95();
      case WARN_80 -> rateLimit.warn80();
      case WARN_50 -> rateLimit.warn50();
      default -> 0;
    };
  }

  private double clampPercent(double value) {
    if (value < 0.0) {
      return 0.0;
    }
    if (value > 100.0) {
      return 100.0;
    }
    return value;
  }
}
