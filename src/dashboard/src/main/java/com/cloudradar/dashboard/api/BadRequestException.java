package com.cloudradar.dashboard.api;

/**
 * Domain-level exception used for request validation failures.
 *
 * <p>Mapped to HTTP 400 by {@link ApiExceptionHandler}.
 */
public class BadRequestException extends RuntimeException {
  /**
   * Creates a bad-request exception with a client-facing message.
   *
   * @param message validation error description
   */
  public BadRequestException(String message) {
    super(message);
  }
}
