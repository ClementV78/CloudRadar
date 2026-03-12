package com.cloudradar.ingester.opensky;

import java.time.Duration;
import java.time.Instant;

class TokenCooldownPolicy {
  private static final long[] TOKEN_FAILURE_BACKOFF_SECONDS = {15L, 30L, 60L, 120L, 300L, 600L};

  private int failureCount;
  private Instant nextAttemptAt = Instant.EPOCH;

  synchronized boolean isBlocked(Instant now) {
    return now.isBefore(nextAttemptAt);
  }

  synchronized long remainingSeconds(Instant now) {
    return Math.max(1L, Duration.between(now, nextAttemptAt).toSeconds());
  }

  synchronized FailureState registerFailure(Instant now) {
    failureCount++;
    int index = Math.min(failureCount - 1, TOKEN_FAILURE_BACKOFF_SECONDS.length - 1);
    long cooldownSeconds = TOKEN_FAILURE_BACKOFF_SECONDS[index];
    nextAttemptAt = now.plusSeconds(cooldownSeconds);
    return new FailureState(failureCount, cooldownSeconds);
  }

  synchronized void reset() {
    failureCount = 0;
    nextAttemptAt = Instant.EPOCH;
  }

  record FailureState(int failureCount, long cooldownSeconds) {}
}
