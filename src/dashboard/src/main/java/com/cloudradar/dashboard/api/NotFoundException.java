package com.cloudradar.dashboard.api;

/**
 * Domain-level exception used when a requested resource does not exist.
 *
 * <p>Mapped to HTTP 404 by {@link ApiExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {
  /**
   * Creates a not-found exception with a client-facing message.
   *
   * @param message missing-resource description
   */
  public NotFoundException(String message) {
    super(message);
  }
}
