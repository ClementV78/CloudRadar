package com.cloudradar.dashboard.api;

/**
 * Domain-level exception used for request throttling and cooldown enforcement.
 *
 * <p>Mapped to HTTP 429 by {@link ApiExceptionHandler}.
 */
public class TooManyRequestsException extends RuntimeException {
  private final long retryAfterSeconds;

  /**
   * Creates a throttling exception with message and retry hint.
   *
   * @param message client-facing message
   * @param retryAfterSeconds seconds before request can be retried
   */
  public TooManyRequestsException(String message, long retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
