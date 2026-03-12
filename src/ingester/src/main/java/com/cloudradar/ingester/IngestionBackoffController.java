package com.cloudradar.ingester;

class IngestionBackoffController {
  private static final long[] FAILURE_BACKOFF_MS = {
    1000L,     // 1s
    2000L,     // 2s
    5000L,     // 5s
    10000L,    // 10s
    30000L,    // 30s
    60000L,    // 60s
    300000L,   // 5m
    600000L,   // 10m
    1800000L,  // 30m
    3600000L   // 1h
  };

  private long nextAllowedAtMs = 0L;
  private int consecutiveFailures = 0;
  private long currentBackoffSeconds = 0L;
  private boolean disabled = false;

  synchronized boolean shouldSkipCycle(long nowMs) {
    if (disabled) {
      return true;
    }
    return nowMs < nextAllowedAtMs;
  }

  synchronized void recordSuccess(long nowMs, long nextDelayMs) {
    consecutiveFailures = 0;
    currentBackoffSeconds = 0L;
    nextAllowedAtMs = nowMs + nextDelayMs;
  }

  synchronized FailureDecision recordFailure(long nowMs) {
    consecutiveFailures++;
    if (consecutiveFailures <= FAILURE_BACKOFF_MS.length) {
      long backoffMs = FAILURE_BACKOFF_MS[consecutiveFailures - 1];
      nextAllowedAtMs = nowMs + backoffMs;
      currentBackoffSeconds = backoffMs / 1000L;
      return new FailureDecision(consecutiveFailures, false, backoffMs);
    }

    disabled = true;
    currentBackoffSeconds = 0L;
    nextAllowedAtMs = Long.MAX_VALUE;
    return new FailureDecision(consecutiveFailures, true, 0L);
  }

  synchronized long currentBackoffSeconds() {
    return currentBackoffSeconds;
  }

  synchronized int disabledGaugeValue() {
    return disabled ? 1 : 0;
  }

  record FailureDecision(int failureCount, boolean disabled, long backoffMs) {}
}
